(ns untangled.i18n.core)

(def *current-locale* (atom "en-US"))

(def *loaded-translations* (atom {}))