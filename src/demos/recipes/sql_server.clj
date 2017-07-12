(ns recipes.sql-server
  (:require [om.next.server :as om]
            [om.next.impl.parser :as op]
            [hugsql.core :as hugsql]
            [taoensso.timbre :as timbre]
            [clojure.set :as set]
            [fulcro.easy-server :as core]
            [com.stuartsierra.component :as component])
  (:import (java.sql Connection DriverManager)
           (org.flywaydb.core Flyway)))

(declare all-people next-person-id insert-person)

(hugsql/def-db-fns "recipes/people.sql")

(defmulti apimutate om/dispatch)
(defmulti api-read om/dispatch)

(defmethod apimutate :default [e k p]
  (timbre/error "Unrecognized mutation " k))

(defmethod apimutate 'app/add-person [{:keys [db] :as env} _ {:keys [id age name]}]
  {:action (fn []
             (let [real-id (-> (next-person-id db) :id)]
               (timbre/info "Inserting person with new id " real-id)
               (insert-person db {:id real-id :name name :age age})
               {:tempids {id real-id}}))})

(defmethod api-read :default [{:keys [ast query] :as env} dispatch-key params]
  (timbre/error "Unrecognized query on dk " dispatch-key " with ast " (op/ast->expr ast)))

(defmethod api-read :people [{:keys [ast query db] :as env} dispatch-key params]
  (let [people (all-people db {})
        result (mapv #(set/rename-keys % {:id :db/id :name :person/name :age :person/age}) people)]
    {:value result}))


(defrecord SQLDatabase [^Connection connection]
  component/Lifecycle
  (start [this]
    (try
      (Class/forName "org.h2.Driver")
      (let [url    "jdbc:h2:mem:default"
            c      (DriverManager/getConnection url)
            flyway ^Flyway (Flyway.)]
        (.setDataSource flyway url "" "" nil)
        (.migrate flyway)
        (assoc this :connection c))
      (catch Exception e
        (timbre/error "Failed to start database " e)
        this)))
  (stop [this]
    (when connection
      (.close connection))
    (dissoc this :connection)))

(defn logging-mutate [env k params]
  (timbre/info "Mutation Request: " k)
  (apimutate env k params))

(defn logging-query [{:keys [ast] :as env} k params]
  (timbre/info "Query: " (op/ast->expr ast))
  (api-read env k params))

(defn make-system []
  (core/make-fulcro-server
    :config-path "config/recipe.edn"
    :parser (om/parser {:read logging-query :mutate logging-mutate})
    :parser-injections #{:db}
    :components {:db (map->SQLDatabase {})}))
