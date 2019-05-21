(ns com.fulcrologic.fulcro.rendering.ident-optimized-render
  (:require
    [com.fulcrologic.fulcro.rendering.keyframe-render :as kr]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.components :as comp]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

(defn props-only-query [query]
  (let [ast (eql/query->ast query)
        ast (update ast :children (fn [cs] (mapv (fn [node]
                                                   (cond-> node
                                                     (= :join (:type node)) (assoc :type :prop)))
                                             cs)))]
    (eql/ast->query ast)))

(defn root-changed? [app]
  (let [{:keys [:com.fulcrologic.fulcro.application/runtime-atom :com.fulcrologic.fulcro.application/state-atom]} app
        {:keys [:com.fulcrologic.fulcro.application/root-class]} @runtime-atom
        state-map       @state-atom
        prior-state-map (-> runtime-atom deref :com.fulcrologic.fulcro.application/last-rendered-state)
        props-query     (props-only-query (comp/get-query root-class state-map))
        root-old        (fdn/db->tree props-query prior-state-map prior-state-map)
        root-new        (fdn/db->tree props-query state-map state-map)]
    (not= root-old root-new)))

(defn dirty-table-entries [old-state new-state idents]
  (reduce
    (fn [result ident]
      (if (identical? (get-in old-state ident) (get-in new-state ident))
        result
        (cons ident result)))
    (list)
    idents))

(defn render-component! [app ident c]
  #?(:cljs
     (let [{:keys [:com.fulcrologic.fulcro.application/state-atom]} app
           state-map @state-atom
           query     (comp/get-query c state-map)
           q         [{ident query}]
           data-tree (when query (fdn/db->tree q state-map state-map))
           new-props (get data-tree ident)]
       (when-not query (log/error "Query was empty. Refresh failed for " (type c)))
       (binding [comp/*app* app]
         (.setState ^js c (fn [s] #js {"fulcro$value" new-props}))))))

(defn render-stale-components! [app]
  (let [{:keys [:com.fulcrologic.fulcro.application/runtime-atom :com.fulcrologic.fulcro.application/state-atom]} app
        {:keys [:com.fulcrologic.fulcro.application/indexes :com.fulcrologic.fulcro.application/last-rendered-state :com.fulcrologic.fulcro.application/components-to-refresh]} @runtime-atom
        {:keys [ident->components]} indexes
        state-map      @state-atom
        mounted-idents (keys ident->components)
        stale-idents   (into (dirty-table-entries last-rendered-state state-map mounted-idents)
                         components-to-refresh)]
    (swap! runtime-atom assoc :com.fulcrologic.fulcro.application/components-to-refresh [])
    (doseq [ident stale-idents]
      (doseq [c (ident->components ident)]
        (render-component! app ident c)))))

(defn render!
  ([app]
   (render! app false))
  ([app force-root?]
   (if (or force-root? (root-changed? app))
     (kr/render! app)
     (render-stale-components! app))))

