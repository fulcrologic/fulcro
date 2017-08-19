(ns recipes.colocated-css
  (:require
    [fulcro.client.core :as fc :refer [InitialAppState initial-state]]
    [fulcro-css.css :as css]
    [om.next :as om :refer [defui]]
    [om.dom :as dom]))

(defonce theme-color (atom :blue))

(defui ^:once Child
  static css/CSS
  ; define local rules via garden. Defined names will be auto-localized
  (local-rules [this] [[:.thing {:color @theme-color}]])
  (include-children [this] [])
  Object
  (render [this]
    (let [{:keys [label]} (om/props this)
          ; use destructuring against the name you invented to get the localized classname
          {:keys [thing]} (css/get-classnames Child)]
      (dom/div #js {:className thing} label))))

(def ui-child (om/factory Child))

(declare change-color)
(defui ^:once Root
  static css/CSS
  (local-rules [this] [])
  ; Compose children with local reasoning. Dedupe is automatic if two UI paths cause re-inclusion.
  (include-children [this] [Child])
  static om/IQuery
  (query [this] [:ui/react-key])
  Object
  (render [this]
    (let [{:keys [ui/react-key child]} (om/props this)]
      (dom/div #js {:key react-key}
        (dom/button #js {:onClick (fn [e]
                                    ; change the atom, and re-upsert the CSS. Look at the elements in your dev console.
                                    ; Figwheel and Closure push SCRIPT tags too, so it may be hard to find on
                                    ; initial load. You might try clicking one of these
                                    ; to make it easier to find (the STYLE will pop to the bottom).
                                    (change-color "blue"))} "Use Blue Theme")
        (dom/button #js {:onClick (fn [e]
                                    (change-color "red"))} "Use Red Theme")
        (ui-child {:label "Hello World"})))))

(defn change-color [c]
  (reset! theme-color c)
  (css/upsert-css "css-id" Root))

; Push the real CSS to the DOM via a component. One or more of these could be done to, for example,
; include CSS from different modules or libraries into different style elements.
(css/upsert-css "css-id" Root)

