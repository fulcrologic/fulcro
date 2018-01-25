(ns book.bootstrap.components.modal-variations
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [fulcro.client.routing :as routing :refer [defrouter]]
            [book.bootstrap.helpers :refer [render-example sample]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [book.bootstrap.components.modals :as modals]
            [fulcro.ui.bootstrap3 :as b]))

(defsc modal-variation-small [t p]
  (render-example "100%" "300px"
    (dom/div nil
      (modals/ui-warning-modal {:message "This is a small modal."
                                :modal   {:id :small :modal/active true :modal/visible true :modal/size :sm :backdrop false}}))))

(def ui-modal-variation-small (prim/factory modal-variation-small))

(defsc modal-variation-default-size [t p]
  (render-example "100%" "300px"
    (dom/div nil
      (modals/ui-warning-modal {:message "This is a regular modal."
                                :modal   {:id :dflt :modal/active true :modal/visible true :backdrop false}}))))

(def ui-modal-variation-default-size (prim/factory modal-variation-default-size))

(defsc modal-variation-large [t p]
  (render-example "1024px" "300px"
    (dom/div nil
      (modals/ui-warning-modal {:message "This is a large modal."
                                :modal   {:id :large :modal/active true :modal/visible true :modal/size :lg :backdrop false}}))))

(def ui-modal-variation-large (prim/factory modal-variation-small))

(defsc GridModal [this props]
  (b/ui-modal (prim/props this)
    (b/ui-modal-title {:key "title"} "A Modal Using a Grid")
    (b/ui-modal-body {:key "my-body"}
      "body"
      (b/row {:key "row"}
        (b/col {:xs 3}
          "Column 1 xs 3")
        (b/col {:xs 3}
          "Column 2 xs 3")
        (b/col {:xs 3}
          "Column 3 xs 3")
        (b/col {:xs 3}
          "Column 4 xs 3")))))

(def ui-grid-modal (prim/factory GridModal {:keyfn :id}))

(defsc modal-with-grid [t p]
  (render-example "100%" "200px"
    (ui-grid-modal {:id :my-modal :modal/visible true :modal/active true})))

(def ui-modal-with-grid (prim/factory modal-with-grid))

(defsc ModalRoot [t p]
  (dom/div nil
    (dom/h4 nil "Small")
    (ui-modal-variation-small {})
    (dom/h4 nil "Default")
    (ui-modal-variation-default-size {})
    (dom/h4 nil "Large")
    (ui-modal-variation-large {})
    (dom/h4 nil "With Grid (modals are allowed to use the grid within the body without a container)")
    (ui-modal-with-grid {})))
