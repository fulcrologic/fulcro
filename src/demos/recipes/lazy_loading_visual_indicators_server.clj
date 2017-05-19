(ns recipes.lazy-loading-visual-indicators-server
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

(defmethod api-read :ui [{:keys [ast query] :as env} dispatch-key params]
  (let [component (second (:key ast))]
    (case component
      :panel {:value {:child {:db/id 5 :child/label "Child"}}}
      :child {:value {:items [{:db/id 1 :item/label "A"} {:db/id 2 :item/label "B"}]}}
      nil)))

(defmethod api-read :items/by-id [{:keys [query-root] :as env} _ params]
  (let [id (second query-root)]
    (timbre/info "Item query for " id)
    {:value {:db/id id :item/label (str "Refreshed Label " (rand-int 100))}}))

(defn logging-mutate [env k params]
  (timbre/info "Mutation Request: " k)
  (apimutate env k params))

(defn logging-query [{:keys [ast] :as env} k params]
  (Thread/sleep 1000)
  (timbre/info "Query: " (op/ast->expr ast))
  (api-read env k params))

(defn make-system []
  (core/make-untangled-server
    :config-path "config/recipe.edn"
    :parser (om/parser {:read logging-query :mutate logging-mutate})
    :parser-injections #{}
    :components {}))
