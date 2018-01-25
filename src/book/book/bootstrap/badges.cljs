(ns book.bootstrap.badges
  (:require [fulcro.client.primitives :refer [defsc]]
            [book.bootstrap.helpers :refer [render-example sample]]
            [fulcro.ui.bootstrap3 :as b]))

(defsc badges

  [t p]
  (render-example "100%" "100px"
    (b/button {} "Inbox " (b/badge {} "1"))))

