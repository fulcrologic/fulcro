(ns recipes.paginate-large-lists-server
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

; start and end come from the top-level query, which we propagate in env
(defmethod api-read :start [{:keys [start]} k p] {:value start})
(defmethod api-read :end [{:keys [end]} k p] {:value end})
(defmethod api-read :items [{:keys [start end]} k p]
  {:value (vec (for [id (range start end)]
                 {:item/id id}))})

(defmethod api-read :load-page [{:keys [state query parser] :as env} k {:keys [start end]}]
  {:value (parser (assoc env :start start :end end) query)})


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
