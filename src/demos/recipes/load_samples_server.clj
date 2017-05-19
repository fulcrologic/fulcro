(ns recipes.load-samples-server
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

(def all-users [{:db/id 1 :person/name "A" :kind :friend}
                {:db/id 2 :person/name "B" :kind :friend}
                {:db/id 3 :person/name "C" :kind :enemy}
                {:db/id 4 :person/name "D" :kind :friend}])

(defmethod api-read :all-users [env _ {:keys [limit]}]
  (timbre/info "Query for all users with a limit of " limit)
  {:value (vec (take limit all-users))}
  )

(defmethod api-read :people/by-id [{:keys [ast query-root] :as env} _ p]
  (let [id     (second (:key ast))
        person (first (filter #(= id (:db/id %)) all-users))]
    (timbre/info "Query for person " id)
    {:value (assoc person :person/age-ms (System/currentTimeMillis))}))

(defmethod api-read :people [env _ {:keys [kind]}]
  (let [result (->> all-users
                 (filter (fn [p] (= kind (:kind p))))
                 (mapv (fn [p] (-> p
                                 (select-keys [:db/id :person/name])
                                 (assoc :person/age-ms (System/currentTimeMillis))))))]
    (timbre/info "Query for people with a kind of " kind " resulting in " result)
    {:value result}))

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
