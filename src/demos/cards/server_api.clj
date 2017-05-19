(ns cards.server-api
  (:require [om.next.server :as om]
            [taoensso.timbre :as timbre]
            [clojure.tools.namespace.repl :refer [disable-reload! ]]
            [om.next.impl.parser :as op]))

(disable-reload!)

(defmulti server-mutate om/dispatch)
(defmulti server-read om/dispatch)

(defmethod server-mutate :default [e k p]
  (timbre/error "Unrecognized mutation " k))

(defmethod server-read :default [{:keys [ast query] :as env} dispatch-key params]
  (timbre/error "Unrecognized query " (op/ast->expr ast)))

