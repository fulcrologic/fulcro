(ns book.bootstrap.icons
  (:require [fulcro.client.primitives :refer [defsc]]
            [fulcro.client.dom :as dom]
            [book.bootstrap.helpers :refer [render-example sample]]
            [fulcro.ui.bootstrap3 :as b]))

(defsc icons
  [t p]
  (render-example "100%" "2450px"
    (let [rows (map #(apply b/row {:className b/text-center :style #js {:marginBottom "5px"}} %)
                 (partition 6 (for [i (sort b/glyph-icons)]
                                (b/col {:xs 2}
                                  (dom/div #js {:style #js {:border "1px solid black" :padding "2px" :wordWrap "break-word"}}
                                    (b/glyphicon {:size "12pt"} i) (dom/br nil) (str i))))))]
      (apply b/container {}
        rows))))

