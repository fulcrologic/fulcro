(ns book.bootstrap.breadcrumbs
  (:require [fulcro.client.primitives :refer [defsc]]
            [devcards.util.edn-renderer :refer [html-edn]]
            [book.bootstrap.helpers :as helper :refer [render-example sample]]
            [fulcro.ui.bootstrap3 :as b]))

(defsc breadcrumbs "Rendering breadcrumbs"
  [t p]
  (render-example "100%" "95px"
    (b/breadcrumbs {}
      (b/breadcrumb-item "Home" (fn [] (js/alert "Go home")))
      (b/breadcrumb-item "Reports" (fn [] (js/alert "Go Reports")))
      (b/breadcrumb-item "Report A"))))

