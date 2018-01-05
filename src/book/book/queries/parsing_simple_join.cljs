(ns book.queries.parsing-simple-join
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [book.queries.parse-runner :refer [ParseRunner ui-parse-runner]]
            [fulcro.client.dom :as dom]))

(def flat-app-state {:a 1 :user/name "Sam" :c 99})

(defn flat-state-read [{:keys [state parser query] :as env} key params]
  (if (= :user key)
    {:value (parser env query)}                             ; recursive call. query is now [:user/name]
    {:value (get @state key)}))                             ; gets called for :user/name :a and :c

(def my-parser (prim/parser {:read flat-state-read}))

(defsc Root [this {:keys [parse-runner]}]
  {:initial-state (fn [params] {:parse-runner (prim/get-initial-state ParseRunner {:query "[:a {:user [:user/name]} :c]"})})
   :query         [{:parse-runner (prim/get-query ParseRunner)}]}
  (dom/div nil
    (ui-parse-runner (prim/computed parse-runner {:parser my-parser :database flat-app-state}))))
