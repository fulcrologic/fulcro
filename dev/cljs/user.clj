(ns cljs.user
  (:require [cljs.tagged-literals :refer [*cljs-data-readers*]]))

(defn pp [form] `(doto ~form (cljs.pprint/pprint)))
(defn cl [form] `(doto ~form (js/console.log)))

(alter-var-root #'*cljs-data-readers* assoc 'spy pp 'log cl)

