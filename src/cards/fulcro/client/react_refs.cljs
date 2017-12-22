(ns fulcro.client.react-refs
  (:require [devcards.core :as dc]
            [fulcro.client.dom :as dom]
            [fulcro.client :as fc]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [goog.object :as gobj]
            [fulcro.client.primitives :as prim :refer [defui defsc InitialAppState initial-state]]
            [fulcro.client.mutations :as m]))

(defsc Root [this props]
  (dom/div nil "TODO"))

(defsc FunctionRefTest [this props]
  (let [focus (fn [evt]
                (when-let [n (gobj/get this "input-field")]
                  (.focus n)))]
    (dom/div nil
      (dom/input #js {:value    (or (prim/get-state this :v) "")
                      :onChange #(prim/set-state! this {:v (.. % -target -value)})
                      :ref      (fn [r] (gobj/set this "input-field" r))})
      (dom/button #js {:onClick focus} "Focus"))))

(defsc StringRefTest [this props]
  (let [focus (fn [evt]
                (let [n (dom/node this "input-field")]
                  (.focus n)))]
    (dom/div nil
      (dom/input #js {;:value "TODO"
                      :ref   "input-field"})
      (dom/button #js {:onClick focus} "Focus"))))

(defcard-fulcro fn-ref-test-1
  "# Tests that we can find the DOM component via a function ref"
  FunctionRefTest)

(defcard-fulcro str-ref-test-1
  "# Tests that we can find the DOM component via a function ref"
  StringRefTest)
