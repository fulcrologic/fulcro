(ns com.fulcrologic.fulcro.algorithms.transit
  "Transit functions for the on-the-wire EDN communication to common remotes. Includes support for Fulcro tempids,
   and can be extended to support additional application-specific data types."
  #?(:clj
     (:refer-clojure :exclude [ref]))
  (:require
    [cognitect.transit :as t]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid #?@(:cljs [:refer [TempId]])])
  ;; babashka has cognitect.transit (Clojure API) but cannot import the com.cognitect.transit Java
  ;; classes (which this ns never actually references) nor the TempId record class. :bb omits the
  ;; whole import; :clj is unchanged. :bb must precede :clj since babashka also matches :clj.
  #?@(:bb  []
      :clj [(:import [com.cognitect.transit
                      TransitFactory WriteHandler ReadHandler]
                     [com.fulcrologic.fulcro.algorithms.tempid TempId])]))


(defonce transit-handlers
  (atom
    {:writers {}
     :readers {}}))

;; babashka collapses every record to one class (sci.impl.records.SciRecord) and every deftype to
;; one class (sci.impl.deftype.SciType), so transit's class-based write-handler dispatch cannot key
;; on individual types. These two runtime classes are the dispatch keys for the :bb writer (see
;; `write-handlers`). They can't be named as literals/Class-forName under SCI, so obtain them from
;; instances. Defined only under :bb.
#?(:bb
   (do
     (def ^:private sci-record-class (class (tempid/tempid)))
     (deftype ^:private BBTypeProbe [])
     (def ^:private sci-type-class (class (->BBTypeProbe)))))

(defn read-handlers
  "Returns a map that can be used for the :handlers key of a transit reader, taken from the current type handler registry."
  []
  (get @transit-handlers :readers {}))

#?(:bb
   (defn write-handlers
     "Returns a map for the :handlers key of a transit writer. Under babashka, type-keyed dispatch is
      impossible (all records/deftypes collapse to one SCI class each), so this returns a single
      dispatcher write-handler per SCI class that looks up the concrete `(type obj)` in the registry
      (whose :writers slot holds raw {:tag :rep} maps under :bb) and delegates."
     []
     (let [dispatch (t/write-handler
                      (fn [obj] (get-in @transit-handlers [:writers (type obj) :tag]))
                      (fn [obj] ((get-in @transit-handlers [:writers (type obj) :rep]) obj))
                      (fn [obj] (str (get-in @transit-handlers [:writers (type obj) :tag]) "#"
                                  ((get-in @transit-handlers [:writers (type obj) :rep]) obj))))]
       {sci-record-class dispatch
        sci-type-class   dispatch}))
   :default
   (defn write-handlers
     "Returns a map that can be used for the :handlers key of a transit writer, taken from the current type handler registry."
     []
     (get @transit-handlers :writers {})))


#?(:cljs
   (defn writer
     "Create a transit writer.

     - `out`: An acceptable output for transit writers.
     - `opts`: (optional) options to pass to `cognitect.transit/writer` (such as handlers)."
     ([] (writer {}))
     ([opts] (t/writer :json (update opts :handlers merge (write-handlers))))))

#?(:clj
   (defn writer
     "Create a transit writer.

     - `out`: An acceptable output for transit writers.
     - `opts`: (optional) options to pass to `cognitect.transit/writer` (such as data type handlers)."
     ([out] (writer out {}))
     ([out opts] (t/writer out :json (update opts :handlers merge (write-handlers))))))

#?(:cljs
   (defn reader
     "Create a transit reader.

     - `opts`: (optional) options to pass to `cognitect.transit/reader` (such as data type handlers)."
     ([] (reader {}))
     ([opts] (t/reader :json (update opts :handlers merge (read-handlers))))))

#?(:clj
   (defn reader
     "Create a transit reader.

     - `opts`: (optional) options to pass to `cognitect.transit/reader` (such as data type handlers)."
     ([in] (reader in {}))
     ([in opts] (t/reader in :json (-> opts (update :handlers merge (read-handlers)))))))

(defn serializable?
  "Checks to see that the value in question can be serialized by the default fulcro writer by actually attempting to
  serialize it.  This is *not* an efficient check."
  [v]
  #?(:bb   (try
             ;; bb's transit writer has no .write interop; use the t/write Clojure API.
             (t/write (writer (java.io.ByteArrayOutputStream.)) v)
             true
             (catch Exception e false))
     :clj  (try
             (.write (writer (java.io.ByteArrayOutputStream.)) v)
             true
             (catch Exception e false))
     :cljs (try
             (.write (writer) v)
             true
             (catch :default e false))))

(defn transit-clj->str
  "Use transit to encode clj data as a string. Useful for encoding initial app state from server-side rendering.

  - `data`: Arbitrary data
  - `opts`: (optional) Options to send when creating a `writer`. Always preserves metadata. Adding :metadata? true/false
    will turn on/off metadata support. Defaults to on."
  ([data] (transit-clj->str data {}))
  ([data opts]
   ;; Support for Datomic Cloud, which uses an older version of transit with no write-meta
   (let [write-meta #?(:cljs t/write-meta
                       :clj (resolve `t/write-meta))
         opts               (cond-> (dissoc opts :metadata?)
                              (and write-meta (not (false? (:metadata? opts)))) (assoc :transform write-meta))]
     #?(:cljs (t/write (writer opts) data)
        :clj
        (with-open [out (java.io.ByteArrayOutputStream.)]
          (t/write (writer out opts) data)
          (.toString out "UTF-8"))))))

(defn transit-str->clj
  "Use transit to decode a string into a clj data structure. Useful for decoding initial app state
   when starting from a server-side rendering."
  ([str] (transit-str->clj str {}))
  ([str opts]
   #?(:cljs (t/read (reader opts) str)
      :clj  (t/read (reader (java.io.ByteArrayInputStream. (.getBytes str "UTF-8")) opts)))))

(s/def ::reader map?)
(s/def ::writer map?)

(>defn type-handler
  "Creates a map that can be registered with Fulcro's transit support.

   * `type` is a `deftype` or `defrecord` that represents your runtime data that you want to support in Transit
   * `tag` is a string that uniquely identifies your type on the wire
   * `type->ground` is a function that can take an instance of your `type` and turn it into something transit already
   knows how to handle.
   * `ground->type` is a function that can take whatever `type->ground` generated and turn it back into your `type`.

   This function returns a map that contains a :reader and :writer key. The value at these keys is suitable for merging
   at the `:handlers` key of a reader or writer's option map.

   See also `install-type-handler!` for adding this to Fulcro's registry of type support."
  [type tag type->ground ground->type]
  [any? string? fn? fn? => (s/keys :req-un [::reader ::writer])]
  ;; Under :bb the writer slot holds raw {:tag :rep} (no t/write-handler object), because bb cannot
  ;; dispatch handlers by individual type; `write-handlers` builds a per-SCI-class dispatcher from
  ;; these raw entries. The reader slot is identical on every platform (dispatch is by wire tag).
  {:writer {type #?(:bb  {:tag tag :rep type->ground}
                    :default (t/write-handler
                               (fn [_] tag)
                               (fn [t] (type->ground t))
                               (fn [r] (str tag "#" r))))}
   :reader {tag (t/read-handler ground->type)}})

(>defn install-type-handler!
  "Install a type handler (generated by `type-handler`) into the global Fulcro transit support registry. This registry
   can be used by any Fulcro-aware facility that needs to use transit for any standard purpose where app-specific type
   support is desired."
  [t]
  [(s/keys :req-un [::reader ::writer]) => nil?]
  (swap! transit-handlers (fn [m]
                            (-> m
                              (update :readers merge (:reader t))
                              (update :writers merge (:writer t)))))
  nil)

;; :bb keys the handler by (type (tempid/tempid)) because the TempId record class can't be referenced
;; cross-ns under SCI, and drops the ^TempId hint (SCI can't resolve hint classes). :clj/:cljs use the
;; imported/referred TempId, unchanged.
#?(:bb
   (defonce install-tempid-handler
     (install-type-handler! (type-handler (type (tempid/tempid)) tempid/tag
                              (fn [tid] (.-id tid))
                              (fn [uuid] (tempid/tempid uuid)))))
   :default
   (defonce install-tempid-handler
     (install-type-handler! (type-handler TempId tempid/tag
                              (fn [^TempId tid] (.-id tid))
                              (fn [uuid] (tempid/tempid uuid))))))
