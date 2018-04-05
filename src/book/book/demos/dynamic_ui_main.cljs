(ns book.demos.dynamic-ui-main
  (:require [fulcro.client.primitives :refer [defsc]]
            [fulcro.client.routing :as r]
            cljs.loader
            [fulcro.client.dom :as dom]))

; This is a "screen" that we want to load with code-splitting modules. See the "demos" build in project.clj. The name
; of the module needs to match the first element of the ident, as that's how the dynamic router figures out what module
; to load.
(defsc Main [this {:keys [label main-prop]}]
  {:query         [r/dynamic-route-key :label :main-prop]
   :initial-state (fn [params] {r/dynamic-route-key :main :label "MAIN" :main-prop "main page data"})
   :ident         (fn [] [:main :singleton])}
  (dom/div {:style {:backgroundColor "red"}}
    (str label " " main-prop)))

(defmethod r/get-dynamic-router-target :main [k] Main)
(cljs.loader/set-loaded! :main)
