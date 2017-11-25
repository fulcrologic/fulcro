(ns cards.declarative-mutation-refresh
  (:require [fulcro.client.dom :as dom]
            [fulcro.client.primitives :as prim :refer [defui]]
            [fulcro.client.dom :as dom]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.core :as fc :refer [defsc]]
            [fulcro.client.data-fetch :as df]))

(defmutation ping-left [params]
  (action [{:keys [state]}]
    (swap! state update-in [:left/by-id 5 :left/value] inc))
  (refresh [env] [:left/value]))

(declare Right)

(defmutation ping-right [params]
  (remote [{:keys [state ast]}]
    (m/returning ast state Right))
  (refresh [env] [:right/value]))

(defsc Left [this {:keys [db/id left/value]} _ _]
  {:query         [:db/id :left/value]
   :initial-state {:db/id 5 :left/value 42}
   :ident         [:left/by-id :db/id]}
  (dom/div (clj->js {:style {:float :left}})
    (dom/button #js {:onClick #(prim/transact! this `[(ping-right {})])} "Ping Right")
    value))

(def ui-left (prim/factory Left {:keyfn :db/id}))

(defsc Right [this {:keys [db/id right/value]} _ _]
  {:query         [:db/id :right/value]
   :initial-state {:db/id 1 :right/value 99}
   :ident         [:right/by-id :db/id]}
  (dom/div (clj->js {:style {:float :right}})
    (dom/button #js {:onClick #(prim/transact! this `[(ping-left {})])} "Ping Left")
    value))

(def ui-right (prim/factory Right {:keyfn :db/id}))

(defsc Root [this {:keys [ui/react-key left right]} _ _]
  {:query         [{:left (prim/get-query Left)}
                   :ui/react-key
                   {:right (prim/get-query Right)}]
   :initial-state {:left {} :right {}}}
  (dom/div (clj->js {:key react-key :style {:width "500px" :height "50px"}})
    (ui-left left)
    (ui-right right)))

(defcard-doc
  "
  # Fulcro 2.0 Declarative Refresh

  In Fulcro 1.0 data model refreshes were indicated purely as follow-on reads at UI-level transactions:

  ```
  (transact! this `[(f) :person/name])
  ```

  This was the model inherited from Om Next; however, since the mutation is really more aware of what data is changing,
  and the UI is indexed by the data model properties, it makes quite a bit of sense for you to be able to declare
  this *on the mutation itself*.

  The mechanism is quite simple: add a `refresh` section on your mutation and return the list of keywords
  for the data that changed:

  ```
  (defmutation ping-left [params]
    (action [{:keys [state]}]
      (swap! state update-in [:left/by-id 5 :left/value] inc))
    (refresh [env] [:left/value]))
  ```

  The above mutation just indicates what it changed: `:left/value`.

  The UI can then drop the follow-on reads:

  "
  (dc/mkdn-pprint-source Right)
  "
  In this case the transaction is running on a component that doesn't query for the data being changed (it is pinging the
  Left component). The built-in refresh list on the mutation takes care of the update!")

(defcard-fulcro mutation-refresh
  "## Demonstration

  This card is a full-stack live demo of this. The buttons update data that the other button displays. The transacts
  on these would normally require follow-on reads or a callback to the parent to refresh properly. With the refresh
  list on the mutation itself, the UI designer is freed from this responsibility.

  The right button uses data from the server in a pessimistic fashion (it does no optimistic update, and there is a simulated
  delay on the server), so pinging if from the left actually reads a value from the server. This demonstrates that the
  refresh is working for full-stack operations."
  Root)
