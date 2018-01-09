(ns book.queries.recursive-demo-2
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.mutations :refer [defmutation]]
            [fulcro.client.dom :as dom]))

(declare ui-person)

(defmutation make-older [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:person/by-id id :person/age] inc)))

(defsc Person [this {:keys [db/id person/name person/spouse person/age]}]
  {:query         (fn [] [:db/id :person/name :person/age {:person/spouse 1}]) ; force limit the depth
   :initial-state (fn [p]
                    ; this does look screwy...you can nest the same map in the recursive position,
                    ; and it'll just merge into the one that was previously normalized during normalization.
                    ; You need to do this or you won't get the loop in the database.
                    {:db/id         1
                     :person/name   "Joe"
                     :person/age    20
                     :person/spouse {:db/id         2
                                     :person/name   "Sally"
                                     :person/age    22
                                     :person/spouse {:db/id 1 :person/name "Joe"}}})
   :ident         [:person/by-id :db/id]}
  (dom/div nil
    (dom/p nil "Name:" name)
    (dom/p nil "Age:" age
      (dom/button #js {:onClick
                       #(prim/transact! this `[(make-older {:id ~id})])} "Make Older"))
    (when spouse
      (dom/ul nil
        (dom/p nil "Spouse:" (ui-person spouse))))))

(def ui-person (prim/factory Person {:keyfn :db/id}))

(defsc Root [this {:keys [person-of-interest]}]
  {:initial-state {:person-of-interest {}}
   :query         [{:person-of-interest (prim/get-query Person)}]}
  (dom/div nil
    (ui-person person-of-interest)))
