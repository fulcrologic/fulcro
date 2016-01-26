(ns untangled.server.protocol-support
  (:require
    [clojure.walk :as walk]
    [com.navis.common.components.authorization :as auth]
    [datomic.api :as d]
    [om.next.server :as om]
    [om.tempid :as t]
    [untangled-spec.core :refer [specification behavior provided component assertions]]
    [untangled.util.fixtures :as fixture]
    [untangled.util.seed :as seed]
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

(defn datomic-id->tempid [stuff]
  (walk/postwalk #(if (datomic-id? %)
                   (set-namespace % "tempid") %)
    stuff))

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
  (let [tempids (atom #{})]
    [(into {}
       (map
         (fn [[k v]]
           (if (symbol? k)
             (do
               (swap! tempids (partial apply conj) (keys (:tempids v)))
               [k (dissoc v :tempids)])
             [k v]))
         stuff))
     @tempids]))

(defn map-keys [f m]
  (into {}
    (for [[k v] m]
      [(f k) v])))

(defn check-server-response
  "`data/server-tx` can has :om.tempid/* & :datomic.id/*.
   - :datomic.id/* should be seeded in `data/seed-data`
   `data/seed-data` can has :datomic.id/*
   `data/response` can has :datomic.id/*

   see assertions inside for what is being tested
   "
  [app {:keys [server-tx seed-data migrations response] :as data} & {:keys [on-success on-error prepare-server-tx]}]
  (let [app+ (.start app)
        tempid-map (get-in app+ [:seeder :seed-result])
        {:keys [api-parser env]} (:handler app+)
        datoid-map (map-keys #(set-namespace % "datomic.id") tempid-map)
        prepare-server-tx+ (if prepare-server-tx
                             #(prepare-server-tx % (partial get datoid-map))
                             identity)
        server-tx+ (prepare-server-tx+ (rewrite-tempids server-tx datoid-map datomic-id?))]
    (let [response+ (try
                      (h/api-handler api-parser env server-tx+)
                      (catch Throwable t
                        (when on-error (on-error t))
                        t)
                      (finally
                        (.stop app+)))]
      (cond
        (and (not on-error) (instance? Throwable response+)) (throw response+)

        (instance? Throwable response+) response+

        :else
        (let [om-tids (collect-om-tempids server-tx+)
              [extracted-response extracted-tempids] (extract-tempids response+)
              extracted-response+ (rewrite-tempids extracted-response
                                                   (clojure.set/map-invert datoid-map)
                                                   integer?)]
          (assertions
            "Server response should contain remappings for all om.tempid's in data/server-tx"
            extracted-tempids => om-tids

            "Server response should match data/response"
            extracted-response+ => response)
          (when on-success (on-success extracted-response+)))))))
