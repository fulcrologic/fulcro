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
     (let [{:com.fulcrologic.fulcro.application/keys [state-atom]} app
           state-map @state-atom
           query     (comp/get-query c state-map)
           q         [{ident query}]
           data-tree (when query (fdn/db->tree q state-map state-map))
           new-props (get data-tree ident)]
       (when-not query (log/error "Query was empty. Refresh failed for " (type c)))
       (when (comp/mounted? c)
         (.setState ^js c (fn [s] #js {"fulcro$value" new-props}))))))

(defn render-stale-components! [app]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom state-atom]} app
        {:com.fulcrologic.fulcro.application/keys [indexes last-rendered-state]} @runtime-atom
        {:keys [ident->components prop->classes idents-in-joins]} indexes
        state-map       @state-atom
        idents-in-joins (or idents-in-joins #{})
        mounted-idents  (concat (keys ident->components) idents-in-joins)
        stale-idents    (dirty-table-entries last-rendered-state state-map mounted-idents)]
    (swap! runtime-atom assoc :com.fulcrologic.fulcro.application/components-to-refresh [])
    (doseq [ident stale-idents]
      ;; Components that are querying for the ident directly
      (when (contains? idents-in-joins ident)
        (let [components (prop->classes ident)]
          (when (seq components)
            (doseq [c components]
              (render-component! app ident c)))))
      ;; Components that HAVE the ident
      (doseq [c (ident->components ident)]
        (render-component! app ident c)))))

(defn render!
  ([app]
   (render! app {}))
  ([app {:keys [force-root? root-props-changed?] :as options}]
   (if (or force-root? root-props-changed?)
     (kr/render! app options)
     (render-stale-components! app))))

