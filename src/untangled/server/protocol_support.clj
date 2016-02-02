(ns untangled.server.protocol-support
  (:require
    [clojure.walk :as walk]
    [untangled-spec.core :refer [specification behavior provided component assertions]]
    [untangled.server.impl.components.handler :as h]))

(defn set-namespace [kw new-ns]
  (keyword new-ns (name kw)))

(defn namespace-match-generator [nspace]
  (fn [x]
    (and (keyword? x) (= nspace (namespace x)))))

(def datomic-id?
  (namespace-match-generator "datomic.id"))

(def om-tempid?
  (namespace-match-generator "om.tempid"))

(def tempid?
  "Is the given keyword a seed data tempid keyword (namespaced to `tempid`)?"
  (namespace-match-generator "tempid"))

(defn walk+state [f x & [init-state]]
  (let [state (atom (or init-state {}))]
    (clojure.walk/postwalk
      #(let [state' (f @state %)]
        (reset! state state')
        %)
      x)
    @state))

(defn collect-om-tempids [x]
  (walk+state (fn [state node]
                (if (om-tempid? node)
                  (conj state node)
                  state))
    x #{}))

(defn rewrite-tempids
  "Rewrite tempid keywords in the given state using the tid->rid map.
   Leaves the keyword alone if the map does not contain an entry for it.
   Only considers things that match `prefix-p` or tempid?"
  [state tid->rid & [prefix-p]]
  (walk/prewalk #(if ((or prefix-p tempid?) %)
                  (get tid->rid % %) %)
    state))

;;TODO: not necessary?
(defn vectors-to-sets
  "Convert nested vectors in the given data into sets (useful for comparison in tests where order should not matter)"
  [data] (walk/prewalk (fn [v]
                         (if (and (vector? v) (map? (first v)))
                           (set v)
                           v)) data))

;;TODO: not necessary?
(defn protocol-data
  "Returns a function "
  [& {:keys [query response seed-data]}]
  (fn [tid->rid]
    {:query     (rewrite-tempids query tid->rid)
     :response  (rewrite-tempids response tid->rid)
     :seed-data seed-data}))

;;TODO: move to protocol_support.cljs
(defn check-delta
  "Checks that `new` includes the `delta`, where `delta` is a map keyed by data path (as in get-in). The
   values of `delta` are literal values to verify at that path (nil means the path should be missing)."
  [new delta]
  (doseq [[key-path value] delta]
    (assertions
      (get-in new key-path) => value)))

(defn extract-tempids
  "returns a tuple where the second element is a set of all the mutation tempids
   and the first is stuff without the :tempids k-v pair in the mutation values"
  [stuff]
  (let [tempids (atom {})]
    [(into {}
       (map
         (fn [[k v]]
           (if (symbol? k)
             (do
               (swap! tempids merge (:tempids v))
               [k (dissoc v :tempids)])
             [k v]))
         stuff))
     @tempids]))

(defn map-keys [f m]
  (into {}
    (for [[k v] m]
      [(f k) v])))

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
      (let [tempid-map (get-in started-app [:seeder :seed-result])
            _ (when-not tempid-map
                (.stop started-app)
                (assert false "seed data tempids must have no overlap"))
            {:keys [api-parser env]} (:handler started-app)
            datomic-tid->rid (map-keys #(set-namespace % "datomic.id") tempid-map)
            prepare-server-tx+ (if prepare-server-tx
                                 #(prepare-server-tx % (partial get datomic-tid->rid))
                                 identity)
            server-tx+ (prepare-server-tx+ (rewrite-tempids server-tx datomic-tid->rid datomic-id?))
            server-response (-> (h/api {:parser api-parser :env env :transit-params server-tx+}) :body)
            om-tids (collect-om-tempids server-tx+)
            [response-without-tempid-remaps om-tempid->datomic-id] (extract-tempids server-response)
            response-to-check (-> response-without-tempid-remaps
                                (rewrite-tempids
                                  (clojure.set/map-invert datomic-tid->rid)
                                  integer?)
                                (rewrite-tempids
                                  (clojure.set/map-invert om-tempid->datomic-id)
                                  integer?))]

        (assertions
          "Server response should contain remappings for all om.tempid's in data/server-tx"
          (set (keys om-tempid->datomic-id)) => om-tids

          "Server response should match data/response"
          response-to-check => response)

        (when on-success (on-success env response-to-check)))

      (finally
        (.stop started-app)))))
