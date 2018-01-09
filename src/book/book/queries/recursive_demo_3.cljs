(ns book.queries.recursive-demo-3
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.mutations :refer [defmutation]]
            [fulcro.client.dom :as dom]))

(declare ui-person)

(defmutation make-older [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:person/by-id id :person/age] inc)))

; We use computed to track the depth. Targeted refreshes will retain the computed they got on
; the most recent render. This allows us to detect how deep we are.
(defsc Person [this
               {:keys [db/id person/name person/spouse person/age]} ; props
               {:keys [render-depth] :or {render-depth 0}}] ; computed
  {:query         (fn [] [:db/id :person/name :person/age {:person/spouse 1}]) ; force limit the depth
   :initial-state (fn [p]
                    {:db/id         1 :person/name "Joe" :person/age 20
                     :person/spouse {:db/id         2 :person/name "Sally"
                                     :person/age    22
                                     :person/spouse {:db/id 1 :person/name "Joe"}}})
   :ident         [:person/by-id :db/id]}
  (dom/div nil
    (dom/p nil "Name:" name)
    (dom/p nil "Age:" age
      (dom/button #js {:onClick
                       #(prim/transact! this `[(make-older {:id ~id})])} "Make Older"))
    (when (and (= 0 render-depth) spouse)
      (dom/ul nil
        (dom/p nil "Spouse:"
          ; recursively render, but increase the render depth so we can know when a
          ; targeted UI refresh would accidentally push the UI deeper.
          (ui-person (prim/computed spouse {:render-depth (inc render-depth)})))))))

(def ui-person (prim/factory Person {:keyfn :db/id}))

(defsc Root [this {:keys [person-of-interest]}]
  {:initial-state {:person-of-interest {}}
   :query         [{:person-of-interest (prim/get-query Person)}]}
  (dom/div nil
    (ui-person person-of-interest)))
