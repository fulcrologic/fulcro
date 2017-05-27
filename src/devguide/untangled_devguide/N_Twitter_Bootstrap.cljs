(ns untangled-devguide.N-Twitter-Bootstrap
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc deftest]]
            [cljs.reader :as r]
            [om.next.impl.parser :as p]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [untangled.ui.elements :as ele]
            [untangled.ui.bootstrap :as b]
            [untangled.client.core :as uc]))

(declare render-example)

(defcard-doc
  "
  # Twitter Bootstrap

  Untangled includes functions that emit the DOM with CSS for version 3 of Twitter's Bootstrap CSS and Components.")

(defn- col [attrs children] (b/col (assoc attrs :className "boxed") children))

(defcard grids
  "
  The four iframes below represent the widths of a large, medium, small, and xsmall screen. The content being
  rendered is:

  ```
  (b/container-fluid {}
    (b/row {}
      (b/col {:xs 12 :md 8} \"xs 12 md 8\") (b/col {:xs 6 :md 4} \"xs 6 md 4\"))
    (b/row {}
      (b/col {:xs 6 :md 4} \"xs 6 md 4\")
      (b/col {:xs 6 :md 4} \"xs 6 md 4\")
      (b/col {:xs 6 :md 4} \"xs 6 md 4\"))
    (b/row {} (b/col {:xs 6 } \"xs 6\") (b/col {:xs 6 } \"xs 6\")))
  ```

  See the Bootstrap documetation for more details.
  "
  (fn [state _]
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
      (render-example "1000px" "100px"
        (b/container-fluid {}
          (b/row {}
            (col {:xs 12 :md 8} "xs 12 md 8") (col {:xs 6 :md 4} "xs 6 md 4"))
          (b/row {}
            (col {:xs 6 :md 4} "xs 6 md 4")
            (col {:xs 6 :md 4} "xs 6 md 4")
            (col {:xs 6 :md 4} "xs 6 md 4"))
          (b/row {} (col {:xs 6} "xs 6") (col {:xs 6} "xs 6"))))
      (dom/h4 nil "Small")
      (render-example "800px" "120px"
        (b/container-fluid {}
          (b/row {}
            (col {:xs 12 :md 8} "xs 12 md 8") (col {:xs 6 :md 4} "xs 6 md 4"))
          (b/row {}
            (col {:xs 6 :md 4} "xs 6 md 4")
            (col {:xs 6 :md 4} "xs 6 md 4")
            (col {:xs 6 :md 4} "xs 6 md 4"))
          (b/row {} (col {:xs 6} "xs 6") (col {:xs 6} "xs 6"))))
      (dom/h4 nil "X-Small")
      (render-example "600px" "120px"
        (b/container-fluid {}
          (b/row {}
            (col {:xs 12 :md 8} "xs 12 md 8") (col {:xs 6 :md 4} "xs 6 md 4"))
          (b/row {}
            (col {:xs 6 :md 4} "xs 6 md 4")
            (col {:xs 6 :md 4} "xs 6 md 4")
            (col {:xs 6 :md 4} "xs 6 md 4"))
          (b/row {} (col {:xs 6} "xs 6") (col {:xs 6} "xs 6")))))))

(defcard typography-lead
  "Lead Text can be generated with `(b/lead {} \"Lead Text\")"
  (render-example "100%" "100%"
    (dom/div nil
      (b/lead {} "Lead Text")
      (dom/p {} "This is a regular paragraph"))))

(defn render-example [width height & children]
  (ele/ui-iframe {:height height :width width}
    (dom/div nil
      (dom/style nil ".boxed {border: 1px solid black}")
      (dom/link #js {:rel         "stylesheet"
                     :href        "/bootstrap-3.3.7/css/bootstrap-theme.min.css"
                     ;:integrity   "sha384-BVYiiSIFeK1dGmJRAkycuHAHRg32OmUcww7on3RYdg4Va+PmSTsz/K68vbdEjh4u"
                     :crossorigin "anonymous"})
      (dom/link #js {:rel         "stylesheet"
                     :href        "/bootstrap-3.3.7/css/bootstrap.min.css"
                     ;:integrity   "sha384-BVYiiSIFeK1dGmJRAkycuHAHRg32OmUcww7on3RYdg4Va+PmSTsz/K68vbdEjh4u"
                     :crossorigin "anonymous"})
      children)))
