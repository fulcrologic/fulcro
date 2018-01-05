(ns book.queries.naive-read
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [book.queries.parse-runner :refer [ParseRunner ui-parse-runner]]
            [fulcro.client.dom :as dom]))

(defn read-42 [env key params] {:value 42})
(def parser-42 (prim/parser {:read read-42}))

(defsc Root [this {:keys [parse-runner]}]
  {:initial-state {:parse-runner {}}
   :query         [{:parse-runner (prim/get-query ParseRunner)}]}
  (dom/div nil
    (ui-parse-runner (prim/computed parse-runner {:parser parser-42 :database {}}))))
