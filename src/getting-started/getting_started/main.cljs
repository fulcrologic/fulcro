(ns getting-started.main
  (:require
    [om.next :as om :refer [defui]]
    [untangled.client.core :as uc]
    [om.dom :as dom]))

(defonce app-1 (atom (uc/new-untangled-client)))

(defui ^:once Root
  Object
  (render [this]
    (let [{:keys [ui/react-key]} (om/props this)]
      (dom/div #js {:key react-key} "Hel World."))))

(swap! app-1 uc/mount Root "app-1")
