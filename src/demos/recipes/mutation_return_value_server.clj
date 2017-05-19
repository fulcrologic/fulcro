(ns recipes.mutation-return-value-server
  (:require [om.next.server :as om]
            [om.next.impl.parser :as op]
            [taoensso.timbre :as timbre]
            [untangled.easy-server :as core]
            [om.next.impl.parser :as op]))

(defmulti apimutate om/dispatch)
(defmulti api-read om/dispatch)

(defmethod apimutate :default [e k p]
  (timbre/error "Unrecognized mutation " k))

(defmethod api-read :default [{:keys [ast query] :as env} dispatch-key params]
  (timbre/error "Unrecognized query " (op/ast->expr ast)))

(defmethod apimutate 'crank-it-up [e k {:keys [value]}]
  {:action (fn [] {:value (inc value)})})


(defn logging-mutate [env k params]
  (timbre/info "Mutation Request: " k)
  (apimutate env k params))

(defn logging-query [{:keys [ast] :as env} k params]
  (timbre/info "Query: " (op/ast->expr ast))
  (api-read env k params))

(defn make-system []
  (core/make-untangled-server
    :config-path "config/recipe.edn"
    :parser (om/parser {:read logging-query :mutate logging-mutate})
    :parser-injections #{}
    :components {}))
