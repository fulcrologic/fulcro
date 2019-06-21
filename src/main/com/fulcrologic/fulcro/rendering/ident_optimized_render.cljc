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

(defn render-components-with-ident!
  "Renders *only* components that *have* the given ident."
  [app ident]
  (doseq [c (comp/ident->components app ident)]
    (render-component! app ident c)))

(defn render-dependents-of-ident!
  "Renders components that have or query for the given ident."
  [app ident]
  (render-components-with-ident! app ident)
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} app
        {:com.fulcrologic.fulcro.application/keys [indexes]} @runtime-atom
        {:keys [prop->classes idents-in-joins class->components]} indexes
        idents-in-joins (or idents-in-joins #{})]
    (when (contains? idents-in-joins ident)
      (let [classes (prop->classes ident)]
        (when (seq classes)
          (doseq [class classes]
            (doseq [component (class->components class)
                    :let [component-ident (comp/get-ident component)]]
              (render-component! app component-ident component))))))))

(defn props->components
  "Given an app and set of props: returns the components that query for those props."
  [app props]
  (when (seq props)
    (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} app
          {:com.fulcrologic.fulcro.application/keys [indexes]} @runtime-atom
          {:keys [prop->classes class->components]} indexes]
      (reduce
        (fn [result prop]
          (let [classes    (prop->classes prop)
                components (reduce #(into %1 (class->components %2)) #{} classes)]
            (into result components)))
        #{}
        props))))

(defn render-stale-components! [app]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom state-atom]} app
        {:com.fulcrologic.fulcro.application/keys [indexes last-rendered-state
                                                   to-refresh only-refresh]} @runtime-atom
        {:keys [ident->components idents-in-joins]} indexes
        limited-refresh? (seq only-refresh)]
    (swap! runtime-atom assoc :com.fulcrologic.fulcro.application/only-refresh [] :com.fulcrologic.fulcro.application/to-refresh [])
    (if limited-refresh?
      (let [{limited-idents true
             limited-props  false} (group-by eql/ident? only-refresh)
            limited-to-render (props->components app limited-props)]
        (doseq [c limited-to-render]
          (render-component! app (comp/get-ident c) c))
        (doseq [i limited-idents]
          (render-components-with-ident! app i)))
      (let [state-map       @state-atom
            idents-in-joins (or idents-in-joins #{})
            {idents-to-force true
             props-to-force  false} (group-by eql/ident? to-refresh)
            mounted-idents  (concat (keys ident->components) idents-in-joins)
            stale-idents    (dirty-table-entries last-rendered-state state-map mounted-idents)
            extra-to-force  (props->components app props-to-force)]
        (doseq [i idents-to-force]
          (render-dependents-of-ident! app i))
        (doseq [c extra-to-force]
          (render-component! app (comp/get-ident c) c))
        (doseq [ident stale-idents]
          (render-dependents-of-ident! app ident))))))

(defn render!
  ([app]
   (render! app {}))
  ([app {:keys [force-root? root-props-changed?] :as options}]
   (if (or force-root? root-props-changed?)
     (kr/render! app options)
     (render-stale-components! app))))

