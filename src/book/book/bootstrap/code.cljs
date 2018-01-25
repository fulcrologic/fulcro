(ns book.bootstrap.code
  (:require [fulcro.client.primitives :refer [defsc]]
            [fulcro.client.dom :as dom]
            [book.bootstrap.helpers :refer [render-example sample]]
            [fulcro.ui.bootstrap3 :as b]))

(defsc FormattingCode
  [t p]
  (render-example "100%" "400px"
    (b/container {}
      (b/row nil
        (b/col {:xs 6} "(dom/code #js {} \"pwd\")")
        (b/col {:xs 6} (dom/code #js {} "pwd")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/kbd #js {} \"CTRL-C\")")
        (b/col {:xs 6} (dom/kbd #js {} "CTRL-C")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/pre #js {} \"(map identity\\n  list)\")")
        (b/col {:xs 6} (dom/pre #js {} "(map identity\n  list)")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/samp #js {} \"This is supposed to represent computer output\")")
        (b/col {:xs 6} (dom/samp #js {} "This is supposed to represent computer output")))
      (dom/hr nil))))


