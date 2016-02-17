(ns untangled.server.protocol-support
  (:require
    [untangled.server.impl.protocol-support :as impl]
    [clojure.walk :as walk]
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
            prepare-server-tx+ (if prepare-server-tx
                                 #(prepare-server-tx % (partial get datomic-tid->rid))
                                 identity)
            server-tx+ (prepare-server-tx+ (impl/rewrite-tempids server-tx datomic-tid->rid))
            server-response (-> (h/api {:parser api-parser :env env :transit-params server-tx+}) :body)
            _ (timbre/debug :server-response server-response)
            om-tids (impl/collect-om-tempids server-tx+)
            [response-without-tempid-remaps om-tempid->datomic-id] (impl/extract-tempids server-response)
            response-to-check (-> response-without-tempid-remaps
                                (impl/rewrite-tempids
                                  (clojure.set/map-invert datomic-tid->rid)
                                  integer?)
                                (impl/rewrite-tempids
                                  (clojure.set/map-invert om-tempid->datomic-id)
                                  integer?))
            _ (timbre/debug :response-to-check response-to-check)]

        (assertions
          "Server response should contain remappings for all om.tempid's in data/server-tx"
          (set (keys om-tempid->datomic-id)) => om-tids

          "Server response should match data/response"
          response-to-check => response)

        (when on-success (on-success env response-to-check)))

      (finally
        (.stop started-app)))))
