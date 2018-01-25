(ns book.bootstrap.grid
  (:require [fulcro.client.primitives :refer [defsc]]
            [fulcro.client.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [book.bootstrap.helpers :refer [render-example sample]]
            [fulcro.ui.elements :as ele]
            [fulcro.ui.bootstrap3 :as b]
            [fulcro.ui.html-entities :as ent]
            [fulcro.client.mutations :as m]))

(defn- col [attrs children] (b/col (assoc attrs :className "boxed") children))

(defsc Grids [this props]
  (dom/div nil
    (dom/h4 nil "Large")
    (render-example "1400px" "100px"
      (b/container-fluid {}
        (b/row {}
          (col {:xs 12 :md 8} "xs 12 md 8") (col {:xs 6 :md 4} "xs 6 md 4"))
        (b/row {}
          (col {:xs 6 :md 4} "xs 6 md 4")
          (col {:xs 6 :md 4} "xs 6 md 4")
          (col {:xs 6 :md 4} "xs 6 md 4"))
        (b/row {} (col {:xs 6} "xs 6") (col {:xs 6} "xs 6"))))
    (dom/h4 nil "Medium")
    (render-example "1000px" "120px"
      (b/container-fluid {}
        (b/row {}
          (col {:xs 12 :md 8} "xs 12 md 8") (col {:xs 6 :md 4} "xs 6 md 4"))
        (b/row {}
          (col {:xs 6 :md 4} "xs 6 md 4")
          (col {:xs 6 :md 4} "xs 6 md 4")
          (col {:xs 6 :md 4} "xs 6 md 4"))
        (b/row {} (col {:xs 6} "xs 6") (col {:xs 6} "xs 6"))))
    (dom/h4 nil "Small")
    (render-example "800px" "140px"
      (b/container-fluid {}
        (b/row {}
          (col {:xs 12 :md 8} "xs 12 md 8") (col {:xs 6 :md 4} "xs 6 md 4"))
        (b/row {}
          (col {:xs 6 :md 4} "xs 6 md 4")
          (col {:xs 6 :md 4} "xs 6 md 4")
          (col {:xs 6 :md 4} "xs 6 md 4"))
        (b/row {} (col {:xs 6} "xs 6") (col {:xs 6} "xs 6"))))
    (dom/h4 nil "X-Small")
    (render-example "600px" "140px"
      (b/container-fluid {}
        (b/row {}
          (col {:xs 12 :md 8} "xs 12 md 8") (col {:xs 6 :md 4} "xs 6 md 4"))
        (b/row {}
          (col {:xs 6 :md 4} "xs 6 md 4")
          (col {:xs 6 :md 4} "xs 6 md 4")
          (col {:xs 6 :md 4} "xs 6 md 4"))
        (b/row {} (col {:xs 6} "xs 6") (col {:xs 6} "xs 6"))))))


