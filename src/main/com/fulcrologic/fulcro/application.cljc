(ns com.fulcrologic.fulcro.application
  (:require
    [com.fulcrologic.fulcro.algorithms.scheduling :as sched]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.mutations :as mut]
    [com.fulcrologic.fulcro.components :as comp]
    #?(:cljs [goog.dom :as gdom])
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.normalize :as fnorm])
  #?(:clj (:import (clojure.lang IDeref))))

(defn basis-t
  "Return the current basis time of the app."
  [app]
  (-> app ::runtime-atom deref ::basis-t))

(defn current-state
  "Get the value of the application state database at the current time."
  [app]
  (let [app (comp/any->app app)]
    (-> app ::state-atom deref)))

(defn tick!
  "Move the basis-t forward one tick."
  [app]
  (swap! (::runtime-atom app) update ::basis-t inc))

(defn render!
  ([app]
   (render! app false))
  ([app force-root]
   (tick! app)
   (binding [fdn/*denormalize-time* (basis-t app)]
     (let [{::keys [runtime-atom state-atom]} app
           {::keys [root-factory root-class mount-node]} @runtime-atom
           state-map @state-atom
           query     (comp/get-query root-class state-map)
           data-tree (fdn/db->tree query state-map state-map)]
       (binding [comp/*app* app]
         (js/ReactDOM.render (root-factory data-tree) mount-node))))))

(defn schedule-render! [app]
  #?(:clj  (render! app)
     :cljs (let [r #(render! app)]
             (if (not (exists? js/requestAnimationFrame))
               (sched/defer r 16)
               (js/requestAnimationFrame r)))))

(defn tx!
  "Submit a new transaction to the given Fulcro app."
  ([app tx]
   (tx! app tx {:optimistic? true}))
  ([{::keys [runtime-atom] :as app} tx options]
   (txn/schedule-activation! app)
   (let [node (txn/tx-node tx options)]
     (swap! runtime-atom update ::txn/submission-queue (fnil conj []) node)
     (schedule-render! app)
     (::txn/id node))))

(defn fulcro-app
  ([] (fulcro-app {}))
  ([options]
   {::state-atom   (atom {})
    ::tx!          tx!
    ::render!      schedule-render!
    ::runtime-atom (atom
                     {::app-root                                                       nil
                      ::mount-node                                                     nil
                      ::root-class                                                     nil
                      ::root-factory                                                   nil
                      ::basis-t                                                        1
                      ::middleware                                                     {:extra-props-middleware (get options :extra-props-middleware)
                                                                                        :render-middleware      (get options :render-middleware)}
                      ::remotes                                                        {:remote (fn [send]
                                                                                                  (log/info "Send"))}
                      ::indexes                                                        {:ident->components {}}
                      ::mutate                                                         mut/mutate
                      :com.fulcrologic.fulcro.transactions/activation-scheduled?       false
                      :com.fulcrologic.fulcro.transactions/queue-processing-scheduled? false
                      :com.fulcrologic.fulcro.transactions/sends-scheduled?            false
                      :com.fulcrologic.fulcro.transactions/submission-queue            []
                      :com.fulcrologic.fulcro.transactions/active-queue                []
                      :com.fulcrologic.fulcro.transactions/send-queues                 {}})}))

(defn fulcro-app? [x] (and (map? x) (contains? x ::state-atom) (contains? x ::runtime-atom)))

(defn mounted? [{::keys [runtime-atom]}]
  (-> runtime-atom deref ::app-root boolean))

(defn merge!
  "Merge an arbitrary data-tree that conforms to the shape of the given query using Fulcro's
  standard merge and normalization logic.

  query - A query, derived from defui components, that can be used to normalized a tree of data.
  data-tree - A tree of data that matches the nested shape of query
  remote - No longer used. May be passed, but is ignored."
  [app data-tree query]
  (let [{:com.fulcrologic.fulcro.application/keys [state-atom]} (comp/any->app app)]
    (when state-atom
      (swap! state-atom merge/merge* query data-tree))))

(defn merge-component!
  "Normalize and merge a (sub)tree of application state into the application using a known UI component's query and ident.

  This utility function obtains the ident of the incoming object-data using the UI component's ident function. Once obtained,
  it uses the component's query and ident to normalize the data and place the resulting objects in the correct tables.
  It is also quite common to want those new objects to be linked into lists in other spot in app state, so this function
  supports optional named parameters for doing this. These named parameters can be repeated as many times as you like in order
  to place the ident of the new object into other data structures of app state.

  This function honors the data merge story for Fulcro: attributes that are queried for but do not appear in the
  data will be removed from the application. This function also uses the initial state for the component as a base
  for merge if there was no state for the object already in the database.

  This function will also trigger re-renders of components that directly render object merged, as well as any components
  into which you integrate that data via the named-parameters.

  This function is primarily meant to be used from things like server push and setTimeout/setInterval, where you're outside
  of the normal mutation story. Do not use this function within abstract mutations.

  - reconciler: A reconciler
  - component: The class of the component that corresponds to the data. Must have an ident.
  - object-data: A map (tree) of data to merge. Will be normalized for you.
  - named-parameter: Post-processing ident integration steps. see integrate-ident!

  Any keywords that appear in ident integration steps will be added to the re-render queue.

  See also `fulcro.client.primitives/merge!`.
  "
  [app component object-data & named-parameters]
  (when-let [app (comp/any->app app)]
    (if-not (comp/has-ident? component)
      (log/error "merge-component!: component must implement Ident. Merge skipped.")
      (let [ident (comp/get-ident component object-data)
            state (:com.fulcrologic.fulcro.application/state-atom app)
            {:keys [merge-data merge-query]} (merge/-preprocess-merge @state component object-data)]
        (merge! app merge-data merge-query)
        (swap! state (fn [s]
                       (as-> s st
                         ;; Use utils until we make smaller namespaces, requiring mutations would
                         ;; cause circular dependency.
                         (apply merge/integrate-ident* st ident named-parameters)
                         (dissoc st :fulcro/merge))))
        (schedule-render! app)))))

(defn merge-alternate-union-elements!
  "Walks the query and initial state of root-component and merges the alternate sides of unions with initial state into
  the application state database. See also `merge-alternate-union-elements`, which can be used on a state map and
  is handy for server-side rendering. This function side-effects on your app, and returns nothing."
  [app root-component]
  (let [app (comp/any->app app)]
    (merge/merge-alternate-unions (partial merge-component! app) root-component)))

(defn mount! [app root node]
  #?(:cljs
     (if (mounted? app)
       (schedule-render! app)
       (let [dom-node     (gdom/getElement node)
             root-factory (comp/factory root)
             root-query   (comp/get-query root)
             initial-tree (comp/get-initial-state root)
             initial-db   (-> (fnorm/tree->db root-query initial-tree true)
                            (merge/merge-alternate-union-elements root))]
         (reset! (::state-atom app) initial-db)
         (swap! (::runtime-atom app) assoc
           ::mount-node dom-node
           ::root-factory root-factory
           ::root-class root)
         (binding [comp/*app* app]
           (let [app-root (js/ReactDOM.render (root-factory initial-tree) dom-node)]
             (swap! (::runtime-atom app) assoc
               ::app-root app-root)))))))
