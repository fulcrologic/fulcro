(ns com.fulcrologic.fulcro.cards.source-annotation-cards
  (:require
    [nubank.workspaces.card-types.fulcro3 :as ct.fulcro]
    [nubank.workspaces.core :as ws]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]))

(defsc SourceAnnotationDemo [this {:thing/keys [id] :as props}]
  {:query         [:thing/id]
   :ident         :thing/id
   :initial-state {:thing/id 1}}
  (let [m {}]
    (dom/div
      (dom/p "nil")
      (dom/p :#paragraph "with css")
      (dom/div {} "map")
      (dom/div {:data-id id} "runtime-map")
      (dom/div m "symbol")
      (dom/div (merge m {}) "expression")
      (dom/div #js {} "js-object")
      (dom/br)
      (dom/div (dom/div "one"))
      (dom/div {} (dom/div "two"))
      (dom/div :.foo "three")
      (dom/div :.foo (dom/div "four"))
      (dom/div :.foo {} (dom/div "five"))
      )))

(ws/defcard source-annotation-demo-card
  (ct.fulcro/fulcro-card
    {::ct.fulcro/wrap-root? true
     ::ct.fulcro/root       SourceAnnotationDemo}))
