(ns com.fulcrologic.fulcro.rendering.ident-optimized-render
  "A render optimization algorithm for refreshing the UI via props tunnelling (setting new props on a component's
  state in a pre-agreed location). This algorithm analyzes database changes and on-screen components to update
  components (by ident) whose props have changed.

  Prop change detection is done by scanning the database in *only* the locations that on-screen components are querying
  (derived by the mounted component idents, and any ident-joins in the queries)."
  (:require
    [com.fulcrologic.fulcro.rendering.keyframe-render :as kr]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.components :as comp]
    [clojure.set :as set]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

(defn dirty-table-entries
  "Checks the given `idents` and returns a subset of them where the data they refer to has changed
   between `old-state` and `new-state`."
  [old-state new-state idents]
  (reduce
    (fn [result ident]
      (if (identical? (get-in old-state ident) (get-in new-state ident))
        result
        (cons ident result)))
    (list)
    idents))

(defn render-component!
  "Uses the component's query and the current application state to query for the current value of that component's
  props (subtree). It then sends those props to the component via \"props tunnelling\" (setting them on a well-known key in
  component-local state)."
  [app ident c]
  #?(:cljs
     (if (and c ident)
       (let [{:com.fulcrologic.fulcro.application/keys [state-atom]} app
             state-map      @state-atom
             query          (comp/get-query c state-map)
             q              [{ident query}]
             prior-computed (or (comp/get-computed c) {})
             data-tree      (when query (fdn/db->tree q state-map state-map)) ; denormalize time is set by app render
             new-props      (comp/computed (get data-tree ident) prior-computed)]
         (when-not query (log/error "Query was empty. Refresh failed for " (type c) "See https://book.fulcrologic.com/#err-id-opt-render-empty-query"))
         (comp/tunnel-props! c new-props))
       (let [root (-> app :com.fulcrologic.fulcro.application/runtime-atom deref :com.fulcrologic.fulcro.application/app-root)]
         (when (not= c root)
           (log/info "Failed to do optimized update. Component" (-> c comp/react-type (comp/class->registry-key))
             "queries for data that changed, but does not have an ident."))
         (throw (ex-info "Targeted update failed" {}))))))

(defn render-components-with-ident!
  "Renders *only* components that *have* the given ident."
  [app ident]
  (doseq [c (comp/ident->components app ident)]
    (render-component! app ident c)))

(defn render-dependents-of-ident!
  "Renders components that have, or query for, the given ident."
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
  "Given an app and a `property-set`: returns the components that query for the items in property-set.

  The `property-set` can be any sequence (ideally a set) of keywords and idents that can directly appear
  in a component query as a property or join key."
  [app property-set]
  (when (seq property-set)
    (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} app
          {:com.fulcrologic.fulcro.application/keys [indexes]} @runtime-atom
          {:keys [prop->classes class->components]} indexes]
      (reduce
        (fn [result prop]
          (let [classes    (prop->classes prop)
                components (reduce #(into %1 (class->components %2)) #{} classes)]
            (into result components)))
        #{}
        property-set))))

(defn render-stale-components!
  "This function tracks the state of the app at the time of prior render in the app's runtime-atom. It
   uses that to do a comparison of old vs. current application state (bounded by the needs of on-screen components).
   When it finds data that has changed it renders all of the components that depend on that data."
  [app]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom state-atom]} app
        {:com.fulcrologic.fulcro.application/keys [indexes last-rendered-state
                                                   to-refresh only-refresh]} @runtime-atom
        {:keys [linked-props ident->components prop->classes idents-in-joins]} indexes
        limited-refresh? (seq only-refresh)]
    (if limited-refresh?
      (let [{limited-idents true
             limited-props  false} (group-by eql/ident? only-refresh)
            limited-to-render (props->components app limited-props)]
        (doseq [c limited-to-render
                :let [ident (comp/get-ident c)]]
          (render-component! app ident c))
        (doseq [i limited-idents]
          (render-dependents-of-ident! app i)))
      (let [state-map          @state-atom
            idents-in-joins    (or idents-in-joins #{})
            dirty-linked-props (reduce
                                 (fn [acc p]
                                   (if (not (identical?
                                              (get state-map p)
                                              (get last-rendered-state p)))
                                     (conj acc p)
                                     acc))
                                 #{}
                                 linked-props)
            {idents-to-force true
             props-to-force  false} (group-by eql/ident? to-refresh)
            props-to-force     (set/union props-to-force dirty-linked-props)
            mounted-idents     (concat (keys ident->components) idents-in-joins)
            stale-idents       (dirty-table-entries last-rendered-state state-map mounted-idents)
            extra-to-force     (props->components app props-to-force)
            all-idents         (set/union (set idents-to-force) (set stale-idents))]
        (doseq [i all-idents]
          (render-dependents-of-ident! app i))
        (doseq [c extra-to-force]
          (render-component! app (comp/get-ident c) c))))))

(defn render!
  "The top-level call for using this optimized render in your application.

  If `:force-root? true` is passed in options, then it just forces a keyframe root render; otherwise
  it tries to minimize the work done for screen refresh to just the queries/refreshes needed by the
  data that has changed."
  ([app]
   (render! app {}))
  ([app {:keys [force-root? root-props-changed?] :as options}]
   (if (or force-root? root-props-changed?)
     (kr/render! app options)
     (try
       (render-stale-components! app)
       (catch #?(:clj Exception :cljs :default) e
         (log/info "Optimized render failed. Falling back to root render.")
         (kr/render! app options))))))

