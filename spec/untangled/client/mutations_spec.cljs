(ns untangled.client.mutations-spec
  (:require
    [untangled-spec.core :refer-macros [specification behavior assertions component]]
    [untangled.client.mutations :as m :refer [defmutation]]
    [goog.debug.Logger.Level :as level]
    [goog.log :as glog]
    [om.next :refer [*logger*]]
    [untangled.client.logging :as log]))

(defmutation sample
  "Doc string"
  [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state assoc :sample id))
  (remote [{:keys [ast]}]
    (assoc ast :params {:x 1})))

(specification "defmutation"
  (component "action"
    (let [state (atom {})
          ast   {}
          env   {:ast ast :state state}
          {:keys [action remote]} (m/mutate env `sample {:id 42})]

      (action)

      (assertions
        "Emits an action that has proper access to env and params"
        (:sample @state) => 42
        "Emits a remote that has the proper value"
        remote => {:params {:x 1}}))))
