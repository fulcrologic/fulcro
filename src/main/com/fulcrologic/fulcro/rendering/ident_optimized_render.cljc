(ns com.fulcrologic.fulcro.rendering.ident-optimized-render
  (:require
    [com.fulcrologic.fulcro.rendering.keyframe-render :as kr]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.components :as comp]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

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
       (when (comp/mounted? c)
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
   (render! app {}))
  ([app {:keys [force-root? root-props-changed?] :as options}]
   (if (or force-root? root-props-changed?)
     (kr/render! app options)
     (render-stale-components! app))))

