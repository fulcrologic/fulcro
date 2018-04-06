(ns book.queries.simple-property-read
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [book.queries.parse-runner :refer [ParseRunner ui-parse-runner]]
            [fulcro.client.dom :as dom]))

(defn property-read [{:keys [state]} key params] {:value (get @state key :not-found)})
(def property-parser (prim/parser {:read property-read}))

(defsc Root [this {:keys [parse-runner]}]
  {:initial-state {:parse-runner {}}
   :query         [{:parse-runner (prim/get-query ParseRunner)}]}
  (dom/div
    (ui-parse-runner (prim/computed parse-runner {:parser property-parser :database {:a 1 :b 2 :c 99}}))))

