(ns book.bootstrap.tables
  (:require [fulcro.client.primitives :refer [defsc]]
            [fulcro.client.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [book.bootstrap.helpers :as helper :refer [render-example sample]]
            [fulcro.ui.bootstrap3 :as b]))

(defsc Tables "Bootstrip includes styles for tables. The `b/table` wrapper handles remember the classes for you."
  [t p]
  (render-example "100%" "650px"
    (dom/div nil
      (dom/h4 nil "A plain table (table class automatically added with `(b/table ...)`:")
      (b/table {}
        (dom/thead nil
          (dom/tr nil
            (dom/th nil "H1")
            (dom/th nil "H2")
            (dom/th nil "H3")))
        (dom/tbody nil
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3"))
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3"))
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3"))))
      (dom/h4 nil "A table with `(b/table { :styles #{:striped :hover} } ...)`:")
      (b/table {:styles #{:striped :hover}}
        (dom/thead nil
          (dom/tr nil
            (dom/th nil "H1")
            (dom/th nil "H2")
            (dom/th nil "H3")))
        (dom/tbody nil
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3"))
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3"))
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3"))))
      (dom/h4 nil "A table with `(b/table { :styles #{:bordered :condensed} } ...)`:")
      (b/table {:styles #{:bordered :condensed}}
        (dom/thead nil
          (dom/tr nil
            (dom/th nil "H1")
            (dom/th nil "H2")
            (dom/th nil "H3")))
        (dom/tbody nil
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3"))
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3"))
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3")))))))

