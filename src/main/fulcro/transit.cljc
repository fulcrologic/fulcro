(ns fulcro.transit
  #?(:clj
     (:refer-clojure :exclude [ref]))
  (:require [cognitect.transit :as t]
    #?(:cljs [com.cognitect.transit :as ct])
            [fulcro.tempid :as tempid #?@(:cljs [:refer [TempId]])])
  #?(:clj
     (:import [com.cognitect.transit
               TransitFactory WriteHandler ReadHandler]
              [fulcro.tempid TempId])))

#?(:cljs
   (deftype TempIdHandler []
     Object
     (tag [_ _] "fulcro/tempid")
     (rep [_ r] (. r -id))
     (stringRep [_ _] nil)))

#?(:clj
   (deftype TempIdHandler []
     WriteHandler
     (tag [_ _] "fulcro/tempid")
     (rep [_ r] (. ^TempId r -id))
     (stringRep [_ r] (. ^TempId r -id))
     (getVerboseHandler [_] nil)))

#?(:cljs
   (defn writer
     ([]
      (writer {}))
     ([opts]
      (t/writer :json
        (assoc-in opts [:handlers TempId] (TempIdHandler.))))))

#?(:clj
   (defn writer
     ([out]
      (writer out {}))
     ([out opts]
      (t/writer out :json
        (assoc-in opts [:handlers TempId] (TempIdHandler.))))))

#?(:cljs
   (defn reader
     ([]
      (reader {}))
     ([opts]
      (t/reader :json
        (assoc-in opts
          [:handlers "fulcro/tempid"]
          (fn [id] (tempid/tempid id)))))))

#?(:clj
   (defn reader
     ([in]
      (reader in {}))
     ([in opts]
      (t/reader in :json
        (assoc-in opts
          [:handlers "fulcro/tempid"]
          (reify
            ReadHandler
            (fromRep [_ id] (TempId. id))))))))

(defn serializable?
  "Checks to see that the value in question can be serialized by the default fulcro writer."
  [v]
  #?(:clj  (try
             (.write (writer (java.io.ByteArrayOutputStream.)) v)
             true
             (catch Exception e false))
     :cljs (try
             (.write (writer) v)
             true
             (catch :default e false))))

(comment
  ;; cljs
  (t/read (reader) (t/write (writer) (tempid/tempid)))

  ;; clj
  (import '[java.io ByteArrayOutputStream ByteArrayInputStream])

  (def baos (ByteArrayOutputStream. 4096))
  (def w (writer baos))
  (t/write w (TempId. (java.util.UUID/randomUUID)))
  (.toString baos)

  (def in (ByteArrayInputStream. (.toByteArray baos)))
  (def r (reader in))
  (t/read r)
  )
