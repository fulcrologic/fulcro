(ns ^:no-doc com.fulcrologic.fulcro.inspect.transit
  (:require [cognitect.transit :as t]
            [com.cognitect.transit.types :as ty]
            [com.fulcrologic.fulcro.algorithms.transit :as ft]
            [taoensso.timbre :as log]))

(deftype ErrorHandler []
  Object
  (tag [this v] "js-error")
  (rep [this v] [(ex-message v) (ex-data v)])
  (stringRep [this v] (ex-message v)))

(deftype DefaultHandler []
  Object
  (tag [this v] "unknown")
  (rep [this v] (try
                  (str v)
                  (catch :default e
                    (when goog.DEBUG
                      (log/warn "Transit was unable to encode a value. See https://book.fulcrologic.com/#warn-transit-encode-failed"))
                    "UNENCODED VALUE"))))

(defn write-handlers []
  (merge {cljs.core/ExceptionInfo (ErrorHandler.)
          "default"               (DefaultHandler.)}
    (ft/write-handlers)))

(defn read-handlers []
  (merge
    {"js-error" (fn [[msg data]] (ex-info msg data))}
    (ft/read-handlers)))

(defn read [str]
  (let [reader (ft/reader {:handlers (read-handlers)})]
    (t/read reader str)))

(defn write [x]
  (let [writer (ft/writer {:handlers (write-handlers)})]
    (t/write writer x)))

(extend-type ty/UUID IUUID)
