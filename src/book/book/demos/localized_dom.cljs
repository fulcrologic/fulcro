(ns book.demos.localized-dom
  (:require
    [fulcro-css.css :as css]
    [fulcro.client.mutations :refer [defmutation]]
    [fulcro.client.primitives :as prim :refer [defsc InitialAppState initial-state]]
    [fulcro.client.localized-dom :as dom]))

(defonce theme-color (atom :blue))

(defsc Child [this {:keys [label invisible?]}]
  {:css           [[:.thing {:color @theme-color}]]
   :query         [:id :label :invisible?]
   :initial-state {:id :param/id :invisible? false :label :param/label}
   :ident         [:child/by-id :id]}
  (dom/div :.thing {:classes [(when invisible? :$hide)]} label))

(def ui-child (prim/factory Child))

(declare change-color)

(defmutation toggle-child [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:child/by-id id :invisible?] not)))

(defsc Root [this {:keys [child]}]
  {:css           [[:$hide {:display :none}]]               ; a global CSS rule ".hide"
   :query         [{:child (prim/get-query Child)}]
   :initial-state {:child {:id 1 :label "Hello World"}}
   :css-include   [Child]}
  (dom/div
    (dom/button {:onClick (fn [e] (change-color "blue"))} "Use Blue Theme")
    (dom/button {:onClick (fn [e] (change-color "red"))} "Use Red Theme")
    (dom/button {:onClick (fn [e] (prim/transact! this `[(toggle-child {:id 1})]))} "Toggle visible")
    (ui-child child)))

(defn change-color [c]
  (reset! theme-color c)
  (css/upsert-css "demo-css-id" Root))

; Push the real CSS to the DOM via a component. One or more of these could be done to, for example,
; include CSS from different modules or libraries into different style elements.
(css/upsert-css "demo-css-id" Root)
