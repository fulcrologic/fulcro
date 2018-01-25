(ns book.bootstrap.helpers
  (:require [fulcro.ui.elements :as ele]
            [fulcro.client.dom :as dom]
            [fulcro.ui.bootstrap3 :as b]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Some internal helpers:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-example [width height & children]
  (ele/ui-iframe {:style {:padding "10px 10px 10px 10px" :backgroundColor "white"} :frameBorder 10 :height height :width width}
    (apply dom/div #js {:key "example-frame-key"}
      (dom/style nil ".boxed {border: 1px solid black}")
      #_(dom/link #js {:rel  "stylesheet"
                       :href "http://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css"})
      (dom/link #js {:rel "stylesheet" :href "bootstrap-3.3.7/css/bootstrap.min.css"})
      children)))

(defn sample [ele description]
  (dom/div #js {:className "thumbnail center-block"}
    ele
    (dom/div #js {:className "caption"}
      (dom/p #js {:className "text-center"} description))))

(defn col [attrs children] (b/col (assoc attrs :className "boxed") children))
