(ns fulcro.client.manual-tests-of-dynamic-queries
  (:require [devcards.core :as dc]
            [fulcro.client.dom :as dom]
            [fulcro.client.core :as fc]
            [goog.object]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.primitives :as prim :refer [defsc InitialAppState initial-state]]
            [fulcro.client.mutations :as m]))

(declare ui-leaf)

(defsc Leaf [this {:keys [x y]} _ _]
  {:initial-state (fn [c params] {:x 1 :y 42})
   :query         (fn [this] [:x])
   :ident         (fn [this props] [:LEAF :ID])}
  (dom/div nil
    (dom/button #js {:onClick (fn [] (prim/set-query! this ui-leaf {:query [:x]}))} "Set query to :x")
    (dom/button #js {:onClick (fn [] (prim/set-query! this ui-leaf {:query [:y]}))} "Set query to :y")
    (dom/button #js {:onClick (fn [e] (if x
                                        (m/set-value! this :x (inc x))
                                        (m/set-value! this :y (inc y))))}
      (str "Count: " (or x y)))
    " Leaf"))

(def ui-leaf (prim/factory Leaf {:qualifier :x}))

(defsc Root [this {:keys [ui/react-key root/leaf] :as props} _ _]
  {:initial-state (fn [t p] {:ui/react-key "A" :root/leaf (prim/get-initial-state Leaf {})})
   :query         (fn [this] [{:root/leaf (prim/get-query ui-leaf)} :ui/react-key])}
  (dom/div #js {:key (or react-key)}
    (ui-leaf leaf)))

(defcard-fulcro union-initial-app-state
  Root
  {}
  {:inspect-data true})

(comment
  ; live manual test of query IDs and pulling query for live components
  (let [reconciler (-> union-initial-app-state-fulcro-app deref :reconciler)
        state      (prim/app-state reconciler)
        indexer    (prim/get-indexer reconciler)
        component  (first (prim/ref->components reconciler [:LEAF :ID]))]
    [(prim/get-query-id component)
     (prim/get-query component @state)                      ;using component instance
     (prim/get-query ui-leaf @state)                        ; using factory
     ])

  )
