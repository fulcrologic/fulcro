(ns untangled.client.impl.protocol-support
  (:require
    [untangled-spec.core :refer-macros [assertions behavior]]
    [cljs.test :refer-macros [is]]
    [clojure.walk :as walk]
    [om.next :as om :refer [defui]]
    [om.dom :as dom]
    [untangled.client.core :as core]))

(defn tempid?
  "Is the given keyword a seed data tempid keyword (namespaced to `tempid`)?"
  [kw] (and (keyword? kw) (= "om.tempid" (namespace kw))))

(defn rewrite-tempids
  "Rewrite tempid keywords in the given state using the tid->rid map. Leaves the keyword alone if the map
   does not contain an entry for it."
  [state tid->rid & [pred]]
  (walk/prewalk #(if ((or pred tempid?) %)
                          (get tid->rid % %) %)
    state))

(defn check-delta
  "Checks that `new-state` includes the `delta`, where `delta` is a map keyed by data path (as in get-in). The
   values of `delta` are literal values to verify at that path (nil means the path should be missing)."
  [new-state delta]
  (if (empty? delta)
    (throw (ex-info "Cannot have empty :merge-delta"
             {:new-state new-state}))
    (doseq [[key-path value] delta]
      (let [behavior-string (:cps/behavior value)
            value (or (:cps/value value) value)]
        (behavior behavior-string
          (if (instance? js/RegExp value)
            (is (re-matches value (get-in new-state key-path)))
            (is (= value (get-in new-state key-path)))))))))

(defn with-behavior [behavior-string value]
  {:cps/value    value
   :cps/behavior behavior-string})

(defn allocate-tempids [tx]
  (let [allocated-ids (atom #{})]
    (walk/prewalk
      (fn [v] (when (tempid? v) (swap! allocated-ids conj v)) v)
      tx)
    (into {} (map #(vector % (om/tempid)) @allocated-ids))))

(defui Root
  static om/IQuery (query [this] [:fake])
  Object (render [this] (dom/div nil "if you see this something is wrong")))

(defn init-testing []
  (-> (core/new-untangled-test-client)
    (core/mount Root "invisible-specs")))
