(ns cards.convenience-macro-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [fulcro.client.core :as fc :refer [defsc]]
    [om.dom :as dom]
    [om.next :as om]))

(defsc Person
  "A person component"
  [this {:keys [db/id person/name]} _ _]
  {:table         :PERSON/by-id
   :props         [:db/id :person/name]
   :initial-state {:db/id :param/id :person/name :param/name}}
  (dom/div nil
    name))

(def ui-person (om/factory Person {:keyfn :db/id}))

(defsc Root
  [this {:keys [people ui/react-key]} _ _]
  {:props         [:ui/react-key]
   :initial-state {:people [{:id 1 :name "Tony"} {:id 2 :name "Sam"} {:id 3 :name "Sally"}]}
   :children      {:people Person}}
  (dom/div #js {:key react-key}
    (mapv ui-person people)))

(defcard-fulcro demo-card
  Root)

