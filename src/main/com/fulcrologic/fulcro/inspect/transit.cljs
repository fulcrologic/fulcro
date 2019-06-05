(ns com.fulcrologic.fulcro.inspect.transit
  (:require [cognitect.transit :as t]
            [com.cognitect.transit.types :as ty]
            [com.fulcrologic.fulcro.algorithms.transit :as ft]))

(deftype ErrorHandler []
  Object
  (tag [this v] "js-error")
  (rep [this v] [(ex-message v) (ex-data v)])
  (stringRep [this v] (ex-message v)))

(deftype DefaultHandler []
  Object
  (tag [this v] "unknown")
  (rep [this v] (pr-str v)))

(def write-handlers
  {cljs.core/ExceptionInfo (ErrorHandler.)
   "default"               (DefaultHandler.)})

(def read-handlers
  {"js-error" (fn [[msg data]] (ex-info msg data))})

(defn read [str]
  (let [reader (ft/reader {:handlers read-handlers})]
    (t/read reader str)))

(defn write [x]
  (let [writer (ft/writer {:handlers write-handlers})]
    (t/write writer x)))

(extend-type ty/UUID IUUID)
