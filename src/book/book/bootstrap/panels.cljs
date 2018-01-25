(ns book.bootstrap.panels
  (:require [fulcro.client.primitives :refer [defsc]]
            [fulcro.client.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [book.bootstrap.helpers :as helper :refer [render-example sample]]
            [fulcro.ui.bootstrap3 :as b]))

(defsc panels
  [t p]
  (render-example "100%" "450px"
    (b/panel nil
      (b/panel-heading nil "Heading without title")
      (b/panel-body nil "This is the body of the panel")
      (b/panel-footer nil "The footer"))
    (b/panel {:kind :danger}
      (b/panel-heading nil
        (b/panel-title nil "Panel Title of :danger panel"))
      (b/panel-body nil "This is the body of the panel"))
    (b/panel nil
      (b/panel-heading nil
        (b/panel-title nil "Panel with a table and no panel-body"))
      (b/table nil
        (dom/tbody nil
          (dom/tr nil
            (dom/th nil "Name")
            (dom/th nil "Address")
            (dom/th nil "Phone"))
          (dom/tr nil
            (dom/td nil "Sally")
            (dom/td nil "555 N Nowhere")
            (dom/td nil "555-1212")))))))
