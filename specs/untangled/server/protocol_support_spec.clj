(ns untangled.server.protocol-support-spec
  (:require
    [clojure.test :as t]
    [com.stuartsierra.component :as component]
    [datomic.api :as d]
    [om.next.server :as om]
    [taoensso.timbre :as timbre]
    [untangled-spec.core :refer [specification behavior provided component assertions]]
    [untangled.datomic.core :refer [resolve-ids build-database]]
    [untangled.datomic.protocols :as udb]
    [untangled.datomic.test-helpers :refer [make-seeder]]
    [untangled.server.core :as core]
    [untangled.server.protocol-support :as ps]))

(t/use-fixtures
  :once #(timbre/with-merged-config
           {:ns-blacklist ["untangled.datomic.*"
                           "untangled.server.protocol-support"
                           "untangled.server.impl.components.handler"
                           "untangled.server.impl.components.config"]}
           (%)))

(defn make-old-one [id name madness]
  {:db/id           id
   :old-one/name    name
   :old-one/madness madness})

(def protocol-support-data
  {:seed-data {:db [(make-old-one :datomic.id/cthulhu "UNSPEAKABLE 1" 13.37)]}
   :server-tx [{:old-one [:old-one/name]}]
   :response  {:old-one [{:old-one/name "UNSPEAKABLE 1"}]}})

(def bad-protocol-support-data
  {:seed-data {:db  [(make-old-one :datomic.id/cthulhu "UNSPEAKABLE 2" 13.37)]
               :db2 [(make-old-one :datomic.id/cthulhu "UNSPEAKABLE" 13.37)]
               :db3 [(make-old-one :datomic.id/yog-sothoth "UNSPEAKABLE" 13.37)]}
   :server-tx [{:old-one [:old-one/name]}]
   :response  {:old-one [{:old-one/name "UNSPEAKABLE 2"}]}})

(def mutate-protocol-support-data
  {:seed-data {:db [(make-old-one :datomic.id/cthulhu "lululululu" 3.14159)]}
   :server-tx '[(old-one/add-follower {:old-one-id        :datomic.id/cthulhu
                                       :follower-id       :om.tempid/follower1
                                       :follower-name     "Follower Won"
                                       :follower-devotion 42.0})
                {:old-one [:old-one/name :old-one/followers :db/id]}]
   :response  {'old-one/add-follower {}
               :old-one              [{:old-one/name      "lululululu",
                                       :old-one/followers [{:db/id :om.tempid/follower1}]
                                       :db/id             :datomic.id/cthulhu}]}})

(defn api-read [{:keys [db query]} k params]
  ;(throw (ex-info "" {:db db}))
  (let [conn (:connection db)]
    (case k
      :old-one {:value (vec (flatten (d/q `[:find (~'pull ?e ~query) :where [?e :old-one/madness]] (d/db conn))))}
      nil)))

(defn mutate [env k {:keys [old-one-id follower-id follower-name follower-devotion]}]
  (case k
    'old-one/add-follower
    {:action (fn []
               (let [connection (.get-connection (:db env))
                     follower-tid (d/tempid :db.part/user)
                     omids->tempids {follower-id follower-tid}]
                 (try
                   (let [tx-data [{:db/id             follower-tid
                                   :follower/name     follower-name
                                   :follower/devotion follower-devotion}
                                  [:db/add old-one-id :old-one/followers follower-tid]]
                         tempids->realids (:tempids @(d/transact connection tx-data))
                         omids->realids (resolve-ids (d/db connection) omids->tempids tempids->realids)]
                     {:tempids omids->realids})
                   (catch Throwable e
                     (throw e)))))}
    nil))

(defrecord TestApiHandler []
    core/Module
    (system-key [_] ::api-handler)
    (components [_] {})
    core/APIHandler
    (api-read [_] api-read)
    (api-mutate [_] mutate))
(defn api-handler [deps]
  (component/using
    (map->TestApiHandler {})
    deps))

(def test-server
  (core/untangled-system
    {:components {:db         (build-database :protocol-support)
                  :config     (core/new-config "test.edn")
                  ::ps/seeder (make-seeder (:seed-data protocol-support-data))}
     :modules [(api-handler [:db])]}))

(def bad-test-server
  (core/untangled-system
    {:modules [(api-handler [:db :db2 :db3])]
     :components {:db         (build-database :protocol-support)
                  :db2        (build-database :protocol-support-2)
                  :db3        (build-database :protocol-support-3)
                  :config     (core/new-config "test.edn")
                  ::ps/seeder (make-seeder (:seed-data bad-protocol-support-data))}}))

(def mutate-test-server
  (core/untangled-system
    {:modules [(api-handler [:db])]
     :components {:db         (build-database :protocol-support)
                  :config     (core/new-config "test.edn")
                  ::ps/seeder (make-seeder (:seed-data mutate-protocol-support-data))}}))

(specification "test server response"
  (behavior "test server response w/ protocol data"
    (ps/check-response-to-client test-server protocol-support-data))
  (behavior "test server response w/ bad protocol data"
    (assertions
      (ps/check-response-to-client bad-test-server bad-protocol-support-data)
      =throws=> (AssertionError #"seed data tempids must have no overlap")))
  (behavior "test server response w/ mutate protocol data"
    (ps/check-response-to-client mutate-test-server mutate-protocol-support-data
                                 :on-success (fn [env resp]
                                               (assertions
                                                 (set (keys env)) => #{:config :db ::api-handler
                                                                       ::core/api-handler :remap-fn
                                                                       ::ps/seeder}
                                                 "seed data is put inside each database"
                                                 (keys (:seed-result (udb/get-info (:db env))))
                                                 => [:datomic.id/cthulhu])))))
