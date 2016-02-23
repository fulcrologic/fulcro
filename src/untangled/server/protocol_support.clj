(ns untangled.server.protocol-support
  (:require
    [untangled.server.impl.protocol-support :as impl]
    [clojure.walk :as walk]
    [om.tempid :as omt]
    [untangled-spec.core :refer [specification behavior provided component assertions]]
    [untangled.server.impl.components.handler :as h]
    [taoensso.timbre :as timbre]))

(defn check-response-to-client
  "Tests that the server responds to a client transaction as specificied by the passed-in protocol data.
   See Protocol Testing README.

  1. `app`: an instance of UntangledServer injected with a `Seeder` component. See Protocl Testing README.
  2. `data`: a map with `server-tx`, the transaction sent from the client to execute on the server, and `response`,
  the expected return value when the server runs the transaction
  3. Optional named parameters
  `on-success`: a function of 2 arguments, taking the parsing environment and the server response for extra validation.
  `prepare-server-tx`: allows you to modify the transaction recevied from the client before running it, using the
  seed result to remap seeded tempids."
  [app {:keys [server-tx response] :as data} & {:keys [on-success prepare-server-tx]}]
  (let [started-app (.start app)]
    (try
      (let [datomic-tid->rid (get-in started-app [:seeder :seed-result])
            _ (timbre/debug :tempid-map datomic-tid->rid)
            _ (when (= :disjoint datomic-tid->rid)
                (.stop started-app)
                (assert false "seed data tempids must have no overlap"))
            {:keys [api-parser env]} (:handler started-app)
            om-tids (impl/collect-om-tempids server-tx)
            prepare-server-tx+ (if prepare-server-tx
                                 #(prepare-server-tx % datomic-tid->rid)
                                 identity)
            [server-tx+ real-omt->fake-omt] (-> (impl/rewrite-tempids server-tx datomic-tid->rid)
                                                impl/rewrite-om-tempids
                                                (update 0 prepare-server-tx+))
            _ (timbre/debug :server-tx server-tx+)
            server-response (-> (h/api {:parser api-parser :env env :transit-params server-tx+}) :body)
            _ (timbre/debug :server-response server-response)
            [response-without-tempid-remaps om-tempid->datomic-id] (impl/extract-tempids server-response)
            response-to-check (-> response-without-tempid-remaps
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
            _ (timbre/debug :response-to-check response-to-check)
            om-tempids-to-check (impl/rewrite-tempids
                                  (set (keys om-tempid->datomic-id))
                                  real-omt->fake-omt
                                  omt/tempid?)]

        (assertions
          "Server response should contain remappings for all om.tempid's in data/server-tx"
          om-tempids-to-check => om-tids

          "Server response should match data/response"
          response-to-check => response)

        (when on-success (on-success env response-to-check)))

      (finally
        (.stop started-app)))))
