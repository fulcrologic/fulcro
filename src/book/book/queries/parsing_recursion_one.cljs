(ns book.queries.parsing-recursion-one
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [book.queries.parse-runner :refer [ParseRunner ui-parse-runner]]
            [fulcro.client.dom :as dom]))

(def database {:window/size  [1920 1200]
               :friends      #{[:people/by-id 1] [:people/by-id 3]}
               :people/by-id {
                              1 {:id 1 :name "Sally" :age 22 :married false}
                              2 {:id 2 :name "Joe" :age 22 :married false}
                              3 {:id 3 :name "Paul" :age 22 :married true}
                              4 {:id 4 :name "Mary" :age 22 :married false}}})

(defn read [{:keys [state query]} key params]
  (case key
    :window/size {:value (get @state :window/size)}
    :friends (let [friend-ids (get @state :friends)
                   get-friend (fn [id] (select-keys (get-in @state id) query))
                   friends    (mapv get-friend friend-ids)]
               {:value friends})
    nil))

(def parser (prim/parser {:read read}))
(def query "[:window/size {:friends [:name :married]}]")

(defsc Root [this {:keys [parse-runner]}]
  {:initial-state (fn [params] {:parse-runner (prim/get-initial-state ParseRunner {:query query})})
   :query         [{:parse-runner (prim/get-query ParseRunner)}]}
  (dom/div nil
    (ui-parse-runner (prim/computed parse-runner {:parser parser :database database}))))
