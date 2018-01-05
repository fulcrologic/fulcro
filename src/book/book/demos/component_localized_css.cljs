(ns book.demos.component-localized-css
  (:require
    [fulcro-css.css :as css]
    [fulcro.client.primitives :as prim :refer [defsc InitialAppState initial-state]]
    [fulcro.client.dom :as dom]))

(defonce theme-color (atom :blue))

(defsc Child [this {:keys [label]} _ {:keys [thing]}]       ;; css destructuring on 4th argument, or use css/get-classnames
  {; define local rules via garden. Defined names will be auto-localized
   :css [[:.thing {:color @theme-color}]]}
  (dom/div #js {:className thing} label))

(def ui-child (prim/factory Child))

(declare change-color)

(defsc Root [this {:keys [ui/react-key]}]
  {; Compose children with local reasoning. Dedupe is automatic if two UI paths cause re-inclusion.
   :css-include [Child]}
  (dom/div nil
    (dom/button #js {:onClick (fn [e]
                                ; change the atom, and re-upsert the CSS. Look at the elements in your dev console.
                                ; Figwheel and Closure push SCRIPT tags too, so it may be hard to find on
                                ; initial load. You might try clicking one of these
                                ; to make it easier to find (the STYLE will pop to the bottom).
                                (change-color "blue"))} "Use Blue Theme")
    (dom/button #js {:onClick (fn [e]
                                (change-color "red"))} "Use Red Theme")
    (ui-child {:label "Hello World"})))

(defn change-color [c]
  (reset! theme-color c)
  (css/upsert-css "demo-css-id" Root))

; Push the real CSS to the DOM via a component. One or more of these could be done to, for example,
; include CSS from different modules or libraries into different style elements.
(css/upsert-css "demo-css-id" Root)
