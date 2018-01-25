(ns book.bootstrap.buttons
  (:require [fulcro.client.primitives :refer [defsc]]
            [fulcro.client.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [book.bootstrap.helpers :as helper :refer [render-example sample]]
            [fulcro.ui.elements :as ele]
            [fulcro.ui.bootstrap3 :as b]
            [fulcro.ui.html-entities :as ent]
            [fulcro.client.mutations :as m]))

(defsc buttons
  [t p]
  (render-example "100%" "250px"
    (apply dom/div nil
      (dom/div #js {}
        "A close button: " (b/close-button {:style #js {:float "none"}}))
      (b/button {:style {:marginTop "10px" :marginLeft "10px"} :key "A"} "Default")
      (b/button {:style {:marginTop "10px" :marginLeft "10px"} :key "b" :size :xs} "Default xs")
      (b/button {:style {:marginTop "10px" :marginLeft "10px"} :key "c" :size :sm} "Default sm")
      (b/button {:style {:marginTop "10px" :marginLeft "10px"} :key "d" :size :lg} "Default lg")
      (for [k [:primary :success :info :warning :danger]
            s [:xs :sm :lg]]
        (b/button {:style {:marginTop "10px" :marginLeft "10px"} :key (str (name k) (name s)) :kind k :size s} (str (name k) " " (name s)))))))


