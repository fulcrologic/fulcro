(ns book.bootstrap.form-fields
  (:require [fulcro.client.primitives :refer [defsc]]
            [fulcro.client.dom :as dom]
            [book.bootstrap.helpers :refer [render-example sample]]
            [fulcro.ui.bootstrap3 :as b]))

(defsc form-fields
  [t p]
  (dom/div nil
    (dom/h4 nil "Normal")
    (render-example "100%" "400px"
      (dom/div #js {}
        (b/labeled-input {:id "name" :type "text"} "Name:")
        (b/labeled-input {:id "address" :type "text" :error "Must not be empty!"} "Address:")
        (b/labeled-input {:id "phone" :type "text" :success "You can leave this blank."} "Phone:")
        (b/labeled-input {:id "email" :type "email" :help "Your primary email"} "Email:")))
    (dom/h4 nil "Horizontal")
    (render-example "100%" "400px"
      (dom/div #js {:className "form-horizontal"}
        (b/labeled-input {:id "name" :split 2 :type "text"} "Name:")
        (b/labeled-input {:id "address" :split 2 :type "text" :error "Must not be empty!"} "Address:")
        (b/labeled-input {:id "phone" :split 2 :type "text"} "Phone:")
        (b/labeled-input {:id "email" :split 2 :type "email" :help "Your primary email"} "Email:")))))



