(ns untangled.i18n.core)

(def ^:dynamic *current-locale* (atom "en-US"))

(def ^:dynamic *loaded-translations* (atom {}))
