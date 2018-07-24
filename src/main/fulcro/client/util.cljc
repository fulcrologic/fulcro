(ns fulcro.client.util
  (:require
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
  "Re-render components. If only a reconciler is supplied then it forces a full React DOM refresh.

  If you supply an additional vector of keywords and idents then it will try to rerender only those components that mention
  those things in their queries."
  ([reconciler keywords]
   (proto/queue! reconciler keywords)
   (proto/schedule-render! reconciler))
  ([reconciler]
   (prim/force-root-render! reconciler)))

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
  ([coll] (transit-clj->str coll {}))
  ([coll opts]
   #?(:cljs (t/write (fulcro.transit/writer opts) coll)
      :clj
      (with-open [out (java.io.ByteArrayOutputStream.)]
        (t/write (fulcro.transit/writer out opts) coll)
        (.toString out "UTF-8")))))

(defn transit-str->clj
  "Use transit to decode a string into a clj data structure. Useful for decoding initial app state when starting from a server-side rendering."
  ([str] (transit-str->clj str {}))
  ([str opts]
   #?(:cljs (t/read (prim/reader opts) str)
      :clj  (t/read (prim/reader (java.io.ByteArrayInputStream. (.getBytes str "UTF-8")) opts)))))

(defn strip-parameters
  "Removes parameters from the query, e.g. for PCI compliant logging."
  [query]
  (-> (clojure.walk/prewalk #(if (map? %) (dissoc % :params) %) (parser/query->ast query)) (parser/ast->expr true)))

(def integrate-ident "DEPRECATED: Now defined in fulcro.client.mutations/integrate-ident*" prim/integrate-ident)
