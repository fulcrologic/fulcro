(ns untangled.client.util
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.spec.alpha :as s]
    clojure.walk
    [om.next :as om]
    [om.next.protocols :as omp]
    [om.next.impl.parser :as parser]
    #?(:clj
    [clojure.spec.gen.alpha :as sg]))
  #?(:clj
     (:import (clojure.lang Atom))))

(defn get-ident
  "Get the ident of an Om class with props. Works on client/server"
  [class props]
  #?(:clj  (when-let [ident (-> class meta :ident)]
             (ident class props))
     :cljs (when (implements? om/Ident class)
             (om/ident class props))))

(defn unique-key
  "Get a unique string-based key. Never returns the same value."
  []
  (let [s #?(:clj (System/currentTimeMillis)
             :cljs (system-time))]
    (str s)))

(defn force-render
  "Re-render components. If only a reconciler is supplied then it forces a full DOM re-render by updating the :ui/react-key
  in app state and forcing Om to re-render the entire DOM, which only works properly if you query
  for :ui/react-key in your Root render component and add that as the react :key to your top-level element.

  If you supply an additional vector of keywords and idents then it will ask Om to rerender only those components that mention
  those things in their queries."
  ([reconciler keywords]
   (omp/queue! reconciler keywords)
   (omp/schedule-render! reconciler))
  ([reconciler]
   (let [app-state (om/app-state reconciler)]
     (do
       (swap! app-state assoc :ui/react-key (unique-key))
       (om/force-root-render! reconciler)))))

(defn atom? [a] (instance? Atom a))

(defn strip-parameters
  "Removes parameters from the query, e.g. for PCI compliant logging."
  [query]
  (-> (clojure.walk/prewalk #(if (map? %) (dissoc % :params) %) (parser/query->ast query)) (parser/ast->expr true)))

(defn deep-merge [& xs]
  "Merges nested maps without overwriting existing keys."
  (if (every? map? xs)
    (apply merge-with deep-merge xs)
    (last xs)))

(defn log-app-state
  "Helper for logging the app-state. Pass in an untangled application atom and either top-level keys, data-paths
   (like get-in), or both."
  [app-atom & keys-and-paths]
  (try
    (let [app-state (om/app-state (:reconciler @app-atom))]
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
      (throw (ex-info "untangled.client.impl.util/log-app-state expects an atom with an untangled client" {})))))

#?(:clj
   (defn conform! [spec x]
     (let [rt (s/conform spec x)]
       (when (s/invalid? rt)
         (throw (ex-info (s/explain-str spec x)
                  (s/explain-data spec x))))
       rt)))

#?(:clj
   (def TRUE (s/with-gen (constantly true) sg/int)))

(defn react-instance?
  "Returns the react-instance (which is logically true) iff the given react instance is an instance of the given react class.
  Otherwise returns nil."
  [react-class react-instance]
  {:pre [react-class react-instance]}
  (when (= (om/react-type react-instance) react-class)
    react-instance))

(defn first-node
  "Finds (and returns) the first child that is an instance of the given React class (or nil if not found)."
  [react-class sequence-of-react-instances]
  (some #(react-instance? react-class %) sequence-of-react-instances))
