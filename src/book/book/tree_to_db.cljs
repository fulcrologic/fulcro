(ns book.tree-to-db
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [fulcro.client.mutations :as m :refer [defmutation]]))

(defsc SubQuery [t p]
  {:ident [:sub/by-id :id]
   :query [:id :data]})

(defsc TopQuery [t p]
  {:ident [:top/by-id :id]
   :query [:id {:subs (prim/get-query SubQuery)}]})

(defmutation normalize-from-to-result [ignored-params]
  (action [{:keys [state]}]
    (let [result (prim/tree->db TopQuery (:from @state) true)]
      (swap! state assoc :result result))))

(defmutation reset [ignored-params] (action [{:keys [state]}] (swap! state dissoc :result)))

(defsc Root [this {:keys [from result]}]
  {:query         [:from :result]
   :initial-state (fn [params]
                    ; some data we're just shoving into the database from root...***not normalized***
                    {:from {:id :top-1 :subs [{:id :sub-1 :data 1} {:id :sub-2 :data 2}]}})}
  (dom/div
    (dom/div
      (dom/h4 "Pretend Incoming Tree")
      (html-edn from))
    (dom/div
      (dom/h4 "Normalized Result (click below to normalize)")
      (when result
        (html-edn result)))
    (dom/button {:onClick (fn [] (prim/transact! this `[(normalize-from-to-result {})]))} "Normalized (Run tree->db)")
    (dom/button {:onClick (fn [] (prim/transact! this `[(reset {})]))} "Clear Result")))


