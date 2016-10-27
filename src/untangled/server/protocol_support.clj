(ns untangled.server.protocol-support
  (:require
    [clojure.test]
    [clojure.walk :as walk]
    [com.stuartsierra.component :as component]
    [om.tempid :as omt]
    [taoensso.timbre :as timbre]
    [untangled-spec.core :refer [specification behavior provided component assertions]]
    [untangled.server.impl.components.handler :as h]
    [untangled.server.impl.protocol-support :as impl]))

(defn check-response-to-client
  "Tests that the server responds to a client transaction as specificied by the passed-in protocol data.
  See Protocol Testing README.

  1. `app`: an instance of UntangledSystem injected with a `::seeder` component. See Protocol Testing README.
  2. `data`: a map with `server-tx`, the transaction sent from the client to execute on the server, and `response`,
  the expected return value when the server runs the transaction
  3. Optional named parameters
  `on-success`: a function of 2 arguments, taking the parsing environment and the server response for extra validation.
  `prepare-server-tx`: allows you to modify the transaction recevied from the client before running it, using the
  seed result to remap seeded tempids."
  [app {:keys [server-tx response] :as data} & {:keys [on-success prepare-server-tx which-db]}]
  (let [system (component/start app)]
    (try
      (let [seeder-result (get-in system [::seeder :seed-result])
            _ (timbre/debug :seeder-result seeder-result)
            _ (when (= :disjoint seeder-result)
                (component/stop system)
                (assert false "seed data tempids must have no overlap"))

            datomic-tid->rid (if which-db
                               (or (get seeder-result which-db)
                                   (throw (ex-info "Invalid which-db"
                                                   {:which-db which-db
                                                    :valid-options (keys seeder-result)})))
                               (apply merge (vals seeder-result)))
            _ (timbre/debug :datomic-tid->rid datomic-tid->rid)
            prepare-server-tx+ (if prepare-server-tx
                                 #(prepare-server-tx % datomic-tid->rid)
                                 identity)
            [server-tx+ real-omt->fake-omt] (-> (impl/rewrite-tempids server-tx datomic-tid->rid)
                                                impl/rewrite-om-tempids
                                                (update 0 prepare-server-tx+))
            _ (timbre/debug :server-tx server-tx+)

            mock-user-claims (-> system :openid-config :value)
            env {:request {:user mock-user-claims}}

            {:keys [handler]} ((:untangled.server.core/api-handler-key (meta system)) system)
            server-response (:body (handler env server-tx+))

            _ (timbre/debug :server-response server-response)
            [response-without-tempid-remaps om-tempid->datomic-id] (impl/extract-tempids server-response)
            rewrite-response #(-> %
                                  ;;datomic rid->tid
                                  (impl/rewrite-tempids
                                    (clojure.set/map-invert datomic-tid->rid)
                                    integer?)
                                  ;;datomic-tid -> om-tempid
                                  (impl/rewrite-tempids
                                    (clojure.set/map-invert om-tempid->datomic-id)
                                    integer?)
                                  ;; om-tempid -> fake-om-tempid
                                  (impl/rewrite-tempids
                                    real-omt->fake-omt
                                    omt/tempid?))
            response-to-check (rewrite-response response-without-tempid-remaps)
            sorted-response-to-check (impl/recursive-sort-by hash response-to-check)
            _ (timbre/debug :response-to-check response-to-check)
            om-tempids-to-check (impl/rewrite-tempids
                                  (set (keys om-tempid->datomic-id))
                                  real-omt->fake-omt
                                  omt/tempid?)
            om-tids (impl/collect-om-tempids server-tx)
            sorted-response (impl/recursive-sort-by hash response)]

        (behavior (str "Server response should contain tempid remappings for: " om-tids)
          (assertions
            om-tempids-to-check => om-tids))

        (assertions
          "Server response should match data/response"
          sorted-response-to-check => sorted-response)

        (when on-success
          (let [env+seed-result (reduce (fn [env [db-name seed-result]]
                                          (assoc-in env [db-name :seed-result] seed-result))
                                        system seeder-result)]
            (on-success (assoc env+seed-result :remap-fn rewrite-response)
                        server-response))))

      (finally
        (component/stop system)))))

(defn with-behavior [_ value] value)
