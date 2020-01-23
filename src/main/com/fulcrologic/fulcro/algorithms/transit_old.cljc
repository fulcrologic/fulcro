(ns com.fulcrologic.fulcro.algorithms.transit-old
  "This version is for use with older versions of Transit, such as what is supported on Datomic Cloud

   Transit functions for the on-the-wire EDN communication to common remotes. Includes support for Fulcro tempids,
   and can be extended to support additional application-specific data types."
  #?(:clj
     (:refer-clojure :exclude [ref]))
  (:require [cognitect.transit :as t]
            #?(:cljs [com.cognitect.transit :as ct])
            [com.fulcrologic.fulcro.algorithms.tempid :as tempid #?@(:cljs [:refer [TempId]])])
  #?(:clj
     (:import [com.cognitect.transit
               TransitFactory WriteHandler ReadHandler]
              [com.fulcrologic.fulcro.algorithms.tempid TempId])))

#?(:cljs
   (deftype TempIdHandler []
            Object
            (tag [_ _] tempid/tag)
            (rep [_ r] (. r -id))
            (stringRep [_ _] nil)))

#?(:clj
   (deftype TempIdHandler []
     WriteHandler
     (tag [_ _] tempid/tag)
     (rep [_ r] (.-id ^TempId r))
     (stringRep [_ r] (str tempid/tag "#" r))
     (getVerboseHandler [_] nil)))

#?(:cljs
   (defn writer
         "Create a transit writer.
         - `out`: An acceptable output for transit writers.
         - `opts`: (optional) options to pass to `cognitect.transit/writer` (such as handlers)."
         ([]
          (writer {}))
         ([opts]
          (t/writer :json
                    (assoc-in opts [:handlers TempId] (TempIdHandler.))))))

#?(:clj
   (defn writer
     "Create a transit writer.
     - `out`: An acceptable output for transit writers.
     - `opts`: (optional) options to pass to `cognitect.transit/writer` (such as data type handlers)."
     ([out]
      (writer out {}))
     ([out opts]
      (t/writer out :json
                (assoc-in opts [:handlers TempId] (TempIdHandler.))))))

#?(:cljs
   (defn reader
         "Create a transit reader.
         - `opts`: (optional) options to pass to `cognitect.transit/reader` (such as data type handlers)."
         ([]
          (reader {}))
         ([opts]
          (t/reader :json
                    (assoc-in opts
                              [:handlers tempid/tag]
                              (fn [id] (tempid/tempid id)))))))

#?(:clj
   (defn reader
     "Create a transit reader.
     - `opts`: (optional) options to pass to `cognitect.transit/reader` (such as data type handlers)."
     ([in]
      (reader in {}))
     ([in opts]
      (t/reader in :json
                (assoc-in opts
                          [:handlers tempid/tag]
                          (reify
                            ReadHandler
                            (fromRep [_ id] (TempId. id))))))))

(defn serializable?
  "Checks to see that the value in question can be serialized by the default fulcro writer by actually attempting to
  serialize it.  This is *not* an efficient check."
  [v]
  #?(:clj  (try
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
  - `opts`: (optional) Options to send when creating a `writer`."
  ([data] (transit-clj->str data {}))
  ([data opts]
   #?(:cljs (t/write (writer opts) data)
      :clj
            (with-open [out (java.io.ByteArrayOutputStream.)]
              (t/write (writer out opts) data)
              (.toString out "UTF-8")))))

(defn transit-str->clj
  "Use transit to decode a string into a clj data structure. Useful for decoding initial app state when starting from a server-side rendering."
  ([str] (transit-str->clj str {}))
  ([str opts]
   #?(:cljs (t/read (reader opts) str)
      :clj  (t/read (reader (java.io.ByteArrayInputStream. (.getBytes str "UTF-8")) opts)))))
