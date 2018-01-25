(ns book.bootstrap.well
  (:require [fulcro.client.primitives :refer [defsc]]
            [book.bootstrap.helpers :refer [render-example sample]]
            [fulcro.ui.bootstrap3 :as b]))

(defsc well [t p]
  (render-example "100%" "120px"
    (b/well nil "This is some content")))

