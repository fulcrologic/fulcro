(ns fulcro.client.util
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.spec.alpha :as s]
    clojure.walk
    [fulcro.client.primitives :as prim]
    [fulcro.client.impl.protocols :as proto]
    fulcro.transit
    [fulcro.util :as util :refer [unique-key]]
    [cognitect.transit :as t]
    [fulcro.client.impl.parser :as parser]
    #?(:clj
    [clojure.spec.gen.alpha :as sg])))

(defn force-render
  "Re-render components. If only a reconciler is supplied then it forces a full DOM re-render by updating the :ui/react-key
  in app state and forcing a re-render of the entire DOM, which only works properly if you query
  for :ui/react-key in your Root render component and add that as the react :key to your top-level element.

  If you supply an additional vector of keywords and idents then it will ask to rerender only those components that mention
  those things in their queries."
  ([reconciler keywords]
   (proto/queue! reconciler keywords)
   (proto/schedule-render! reconciler))
  ([reconciler]
   (let [app-state (prim/app-state reconciler)]
     (do
       (swap! app-state assoc :ui/react-key (unique-key))
       (prim/force-root-render! reconciler)))))

#?(:cljs
   (defn log-app-state
     "Helper for logging the app-state. Pass in a Fulcro application atom and either top-level keys, data-paths
      (like get-in), or both."
     [app-atom & keys-and-paths]
     (try
       (let [app-state (prim/app-state (:reconciler @app-atom))]
         (pprint
           (letfn [(make-path [location]
                     (if (sequential? location) location [location]))
                   (process-location [acc location]
                     (let [path (make-path location)]
                       (assoc-in acc path (get-in @app-state path))))]

             (condp = (count keys-and-paths)
               0 @app-state
               1 (get-in @app-state (make-path (first keys-and-paths)))
               (reduce process-location {} keys-and-paths)))))
       (catch #?(:cljs js/Error :clj Exception) e
         (throw (ex-info "fulcro.client.impl.util/log-app-state expects an atom with a Fulcro client" {}))))))

(defn react-instance?
  "Returns the react-instance (which is logically true) iff the given react instance is an instance of the given react class.
  Otherwise returns nil."
  [react-class react-instance]
  {:pre [react-class react-instance]}
  (when (= (prim/react-type react-instance) react-class)
    react-instance))

(defn first-node
  "Finds (and returns) the first instance of the given React class (or nil if not found) in a sequence of instances. Useful
  for finding a child of the correct type when nesting react components."
  [react-class sequence-of-react-instances]
  (some #(react-instance? react-class %) sequence-of-react-instances))

(defn transit-clj->str
  "Use transit to encode clj data as a string. Useful for encoding initial app state from server-side rendering."
  [coll]
  #?(:cljs (t/write (fulcro.transit/writer) coll)
     :clj
           (with-open [out (java.io.ByteArrayOutputStream.)]
             (t/write (fulcro.transit/writer out) coll)
             (.toString out "UTF-8"))))

(defn transit-str->clj
  "Use transit to decode a string into a clj data structure. Useful for decoding initial app state when starting from a server-side rendering."
  [str]
  #?(:cljs (t/read (prim/reader) str)
     :clj  (t/read (prim/reader (java.io.ByteArrayInputStream. (.getBytes str "UTF-8"))))))

(defn strip-parameters
  "Removes parameters from the query, e.g. for PCI compliant logging."
  [query]
  (-> (clojure.walk/prewalk #(if (map? %) (dissoc % :params) %) (parser/query->ast query)) (parser/ast->expr true)))

(defn integrate-ident
  "Integrate an ident into any number of places in the app state. This function is safe to use within mutation
  implementations as a general helper function.

  The named parameters can be specified any number of times. They are:

  - append:  A vector (path) to a list in your app state where this new object's ident should be appended. Will not append
  the ident if that ident is already in the list.
  - prepend: A vector (path) to a list in your app state where this new object's ident should be prepended. Will not append
  the ident if that ident is already in the list.
  - replace: A vector (path) to a specific location in app-state where this object's ident should be placed. Can target a to-one or to-many.
   If the target is a vector element then that element must already exist in the vector."
  [state ident & named-parameters]
  {:pre [(map? state)]}
  (let [actions (partition 2 named-parameters)]
    (reduce (fn [state [command data-path]]
              (let [already-has-ident-at-path? (fn [data-path] (some #(= % ident) (get-in state data-path)))]
                (case command
                  :prepend (if (already-has-ident-at-path? data-path)
                             state
                             (do
                               (assert (vector? (get-in state data-path)) (str "Path " data-path " for prepend must target an app-state vector."))
                               (update-in state data-path #(into [ident] %))))
                  :append (if (already-has-ident-at-path? data-path)
                            state
                            (do
                              (assert (vector? (get-in state data-path)) (str "Path " data-path " for append must target an app-state vector."))
                              (update-in state data-path conj ident)))
                  :replace (let [path-to-vector (butlast data-path)
                                 to-many?       (and (seq path-to-vector) (vector? (get-in state path-to-vector)))
                                 index          (last data-path)
                                 vector         (get-in state path-to-vector)]
                             (assert (vector? data-path) (str "Replacement path must be a vector. You passed: " data-path))
                             (when to-many?
                               (do
                                 (assert (vector? vector) "Path for replacement must be a vector")
                                 (assert (number? index) "Path for replacement must end in a vector index")
                                 (assert (contains? vector index) (str "Target vector for replacement does not have an item at index " index))))
                             (assoc-in state data-path ident))
                  (throw (ex-info "Unknown post-op to merge-state!: " {:command command :arg data-path})))))
      state actions)))
