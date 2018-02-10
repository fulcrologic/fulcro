(ns book.bootstrap.popover
  (:require [fulcro.client.primitives :refer [defsc]]
            [fulcro.client.dom :as dom]
            [book.bootstrap.helpers :as helper :refer [render-example sample]]
            [fulcro.ui.bootstrap3 :as b]
            [fulcro.client.mutations :as m]
            [fulcro.client.primitives :as prim]))

(defsc popover
  [this {:keys [ui/active]}]
  {:ident (fn [] [:popover :example])
   :query [:ui/active]}
  (render-example "100%" "300px"
    (b/button {:onClick #(m/toggle! this :ui/active)} "Toggle All")
    (dom/br nil)
    (dom/br nil)
    (dom/br nil)
    (dom/br nil)
    (dom/br nil)
    (dom/br nil)
    (b/container nil
      (b/row nil
        (b/col {:xs-offset 2 :xs 2}
          (b/ui-popover {:active active :orientation :left}
            (b/ui-popover-title {} (dom/b nil "Some ") (dom/i nil "Title"))
            (b/ui-popover-content {} "Left Bubble")
            (b/ui-popover-target {} (b/button {} "Left"))))
        (b/col {:xs 2}
          (b/ui-popover {:active active :orientation :top}
            (b/ui-popover-title {:className "extra-class" :style {:color :red}} "Some title")
            (b/ui-popover-content {} "Top Bubble")
            (b/ui-popover-target {} (b/glyphicon {} :question-sign))))
        (b/col {:xs 2}
          (b/ui-popover {:active active :orientation :bottom}
            (b/ui-popover-title {} "Some title")
            (b/ui-popover-content {} "Bottom Bubble")
            (b/ui-popover-target {} (b/button {} "Bottom"))))
        (b/col {:xs 2}
          (b/ui-popover {:active active :orientation :right}
            (b/ui-popover-title {} "Some title")
            (b/ui-popover-content {} "Right Bubble")
            (b/ui-popover-target {} (b/glyphicon {:size "33pt"} :question-sign))))))))

(def ui-popovers (prim/factory popover))

(defsc Root [this {:keys [example]}]
  {:query [{:example (prim/get-query popover)}]}
  (ui-popovers example))
