(ns cards.defrouter-list-and-editor-cards
  (:require [om.dom :as dom]
            [om.next :as om :refer [defui]]
            [om.dom :as dom]
            [recipes.defrouter-list-and-editor :as ex]
            [fulcro.client.mutations :as m]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.core :as fc]))

(defcard-doc
  "# Routing Between a List and an Editor


  ")

(defcard-fulcro list-and-editor
  ex/DemoRoot
  {}
  {:inspect-data true
   :fulcro       {:started-callback
                  (fn [app]
                    ; simulate a load of people via a simple integration of some tree data
                    (fc/merge-state! app ex/PersonList
                      {:people [
                                (ex/make-person 1 "Tony")
                                (ex/make-person 2 "Sally")
                                (ex/make-person 3 "Allen")
                                (ex/make-person 4 "Luna")]}))}})



