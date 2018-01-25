(ns book.bootstrap.button-groups
  (:require [fulcro.client.primitives :refer [defsc]]
            [fulcro.client.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [book.bootstrap.helpers :as helper :refer [render-example sample]]
            [fulcro.ui.bootstrap3 :as b]))

(defsc button-groups
  [t p]
  (dom/div nil
    (render-example "100%" "530px"
      (b/container-fluid {}
        (dom/h4 nil "Regular")
        (b/button-group {} (b/button {} "A") (b/button {} "B") (b/button {} "C"))
        (dom/br nil)
        (dom/h4 nil "xs")
        (b/button-group {:size :xs} (b/button {} "A") (b/button {} "B") (b/button {} "C"))
        (dom/br nil)
        (dom/h4 nil "sm")
        (b/button-group {:size :sm} (b/button {} "A") (b/button {} "B") (b/button {} "C"))
        (dom/br nil)
        (dom/h4 nil "lg")
        (b/button-group {:size :lg} (b/button {} "A") (b/button {} "B") (b/button {} "C"))
        (dom/h4 nil "vertical")
        (b/button-group {:kind :vertical} (b/button {} "A") (b/button {} "B") (b/button {} "C"))
        (dom/h4 nil "justified")
        (b/button-group {:kind :justified} (b/button {} "A") (b/button {} "B") (b/button {} "C"))))
    (render-example "100%" "75px"
      (b/container-fluid {}
        (dom/h4 nil "")
        (b/button-toolbar {}
          (b/button-group {} (b/button {} "A") (b/button {} "B") (b/button {} "C"))
          (b/button-group {} (b/button {} "D") (b/button {} "E")))))))


