(ns cards.mutation-join-cards
  (:require [fulcro.client.dom :as dom]
            [fulcro.client.primitives :as prim :refer [defui]]
            [fulcro.client.dom :as dom]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.core :as fc :refer [defsc]]
            [fulcro.client.data-fetch :as df]))

(declare Item)

(defmutation change-label [{:keys [db/id]}]
  (remote [{:keys [ast state]}]
    (-> ast
      (m/returning state Item)
      (m/with-params {:db/id id}))))

(defsc Item [this {:keys [db/id item/value]} _ _]
  {:query [:db/id :item/value]
   :ident [:item/by-id :db/id]}
  (dom/li #js {:onClick (fn [evt]
                          (prim/transact! this `[(change-label {:db/id ~id})]))} value))

(def ui-item (prim/factory Item {:keyfn :db/id}))

(defsc ItemList [this {:keys [db/id list/title list/items] :as props} _ _]
  {:query [:db/id :list/title {:list/items (prim/get-query Item)}]
   :ident [:list/by-id :db/id]}
  (js/console.log id)
  (dom/div nil
    (dom/h3 nil title)
    (dom/ul nil (map ui-item items))))

(def ui-list (prim/factory ItemList {:keyfn :db/id}))

(defsc Root [this {:keys [ui/react-key mutation-join-list]} _ _]
  {:query [:ui/react-key {:mutation-join-list (prim/get-query ItemList)}]}
  (dom/div #js {:key react-key}
    "Test"
    (ui-list mutation-join-list)))

(defcard-fulcro mutation-merge
  Root
  {}
  {:inspect-data true
   :fulcro       {:started-callback (fn [app] (df/load app :mutation-join-list ItemList))}})

