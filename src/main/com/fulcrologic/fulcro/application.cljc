(ns com.fulcrologic.fulcro.application
  (:require
    [com.fulcrologic.fulcro.algorithms.scheduling :as sched]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.mutations :as mut]
    [com.fulcrologic.fulcro.components :as comp]
    [taoensso.timbre :as log])
  #?(:clj (:import (clojure.lang IDeref))))

(defn fulcro-app
  ([] (fulcro-app {}))
  ([options]
   {::state-atom   (atom {})
    ::runtime-atom (atom {::app-root                                        nil
                          ::middleware                                      {:extra-props-middleware (get options :extra-props-middleware)
                                                                             :render-middleware      (get options :render-middleware)}
                          ::remotes                                         {:remote (fn [send] (log/info "Send"))}
                          ::indexes                                         {:ident->components {}}
                          ::mutate                                          mut/mutate
                          :com.fulcrologic.fulcro.transactions/activation-scheduled?       false
                          :com.fulcrologic.fulcro.transactions/queue-processing-scheduled? false
                          :com.fulcrologic.fulcro.transactions/sends-scheduled?            false
                          :com.fulcrologic.fulcro.transactions/submission-queue            []
                          :com.fulcrologic.fulcro.transactions/active-queue                []
                          :com.fulcrologic.fulcro.transactions/send-queues                 {}})}))

(defn fulcro-app? [x] (and (map? x) (contains? x ::state-atom) (contains? x ::runtime-atom)))

(defn render! [app])

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
     (::txn/id node))))

(defn merge!
  "Merge an arbitrary data-tree that conforms to the shape of the given query using Fulcro's
  standard merge and normalization logic.

  query - A query, derived from defui components, that can be used to normalized a tree of data.
  data-tree - A tree of data that matches the nested shape of query
  remote - No longer used. May be passed, but is ignored."
  ([{:com.fulcrologic.fulcro.application/keys [state-atom]} data-tree query]
   (swap! state-atom merge/merge* query data-tree)))

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
      (schedule-render! app))))
