(ns book.bootstrap.alerts
  (:require [fulcro.client.primitives :refer [defsc]]
            [fulcro.client.dom :as dom]
            [book.bootstrap.helpers :refer [render-example sample]]
            [fulcro.ui.bootstrap3 :as b]))

(defsc alerts "# Alerts

  `(b/alert {:kind :success} \"Oh boy!\")`

  An box with a close button and contextual :kind (:success, :danger, :warning, ...), and
  optional `onClose` handler you supply.
  "
  [t p]
  (render-example "100%" "300px"
    (b/alert {:kind :success} "Oh boy!")
    (dom/br nil)
    (b/alert nil "Things went wrong!")
    (dom/br nil)
    (b/alert {:onClose identity :kind :warning} "I'm worried. (with an onClose)")))

