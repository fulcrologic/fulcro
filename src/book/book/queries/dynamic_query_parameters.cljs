(ns book.queries.dynamic-query-parameters
  (:require
    [fulcro.client.dom :as dom]
    [goog.object]
    [fulcro.client.primitives :as prim :refer [defsc]]))

; This component has a query parameter that can be set to whatever we want dynamically
(defsc Leaf [this {:keys [x y] :as props}]
  {:initial-state (fn [params] {:x 1 :y 99})
   :query         (fn [] '[:x ?additional-stuff])           ; the parameter ?additional-stuff starts out empty
   :ident         (fn [] [:LEAF :ID])}
  (dom/div nil
    (dom/button #js {:onClick (fn [] (prim/set-query! this Leaf {:params {:additional-stuff :y}}))} "Add :y to query")
    (dom/button #js {:onClick (fn [] (prim/set-query! this Leaf {:params {}}))} "Drop :y from query")
    (dom/ul nil
      (dom/li nil "x: " x)
      (dom/li nil "y: " y))))

(def ui-leaf (prim/factory Leaf))

(defsc Root [this {:keys [root/leaf] :as props}]
  {:initial-state (fn [p] {:root/leaf (prim/get-initial-state Leaf {})})
   :query         (fn [] [{:root/leaf (prim/get-query ui-leaf)}])}
  (dom/div nil (ui-leaf leaf)))
