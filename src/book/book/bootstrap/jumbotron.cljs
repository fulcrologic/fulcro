(ns book.bootstrap.jumbotron
  (:require [fulcro.client.primitives :refer [defsc]]
            [fulcro.client.dom :as dom]
            [book.bootstrap.helpers :refer [render-example sample]]
            [fulcro.ui.bootstrap3 :as b]))

(defsc jumbotron
  [t p]
  (render-example "100%" "330px"
    (b/container nil
      (b/row nil
        (b/col {:xs 8 :xs-offset 2}
          (b/jumbotron {}
            (dom/h1 nil "Title")
            (dom/p nil "There is some fancy stuff going on here!")
            (b/button {:kind :primary} "Learn More!")))))))

