(ns untangled.server.impl.protocol-support
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
  (walk/prewalk #(if ((or prefix-p datomic-id?) %)
                  (get tid->rid % %) %)
    state))

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
