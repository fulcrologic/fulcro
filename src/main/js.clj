(ns js
  (:require [fulcro.i18n :as ic])
  (:import (com.ibm.icu.text MessageFormat)
           (java.util Locale)))

(defn- current-locale [] @ic/*current-locale*)

(defn- translations-for-locale [] (get @ic/*loaded-translations* (current-locale)))

(defn tr
  [msg]
  (let [msg-key (str "|" msg)
        translations (translations-for-locale)
        translation (get translations msg-key msg)]
    translation))

(defn trc
  [ctxt msg]
  (let [msg-key (str ctxt "|" msg)
        translations (translations-for-locale)
        translation (get translations msg-key msg)]
    translation))

(defn trf
  [fmt & {:keys [] :as args}]
  (try
    (let [argmap (into {} (map (fn [[k v]] [(name k) v]) args))
          _ (println argmap)
          msg-key (str "|" fmt)
          translations (translations-for-locale)
          translation (get translations msg-key fmt)
          formatter (new MessageFormat translation (Locale/forLanguageTag (current-locale)))]
      (.format formatter argmap))
    (catch Exception e "???")))
