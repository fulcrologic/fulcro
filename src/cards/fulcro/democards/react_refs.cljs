(ns fulcro.democards.react-refs
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
  ; create component-specific functions that will remain the same for the component lifetime, so we're not
  ; causing them to change (using anon functions) all the time. In general, anon functions are not really that
  ; big of an overhead or problem, but this just shows you how you'd do it if you didn't want the parameters to
  ; a DOM element changing all the time.
  {:componentWillMount (fn []
                         (gobj/set this "set-ref" (fn [r] (when r (gobj/set this "input-field" r))))
                         (gobj/set this "set-value" (fn [evt] (prim/set-state! this {:v (.. evt -target -value)}))))}
  (let [focus (fn [evt]
                (when-let [n (gobj/get this "input-field")]
                  (.focus n)))]
    (dom/div nil
      (dom/input #js {:value    (or (prim/get-state this :v) "")
                      :onChange (gobj/get this "set-value")
                      :ref      (gobj/get this "set-ref")})
      (dom/button #js {:onClick focus} "Focus"))))

(defsc StringRefTest [this props]
  {:componentWillMount (fn []
                         (gobj/set this "set-value" (fn [evt] (prim/set-state! this {:v (.. evt -target -value)}))))}
  (let [focus (fn [evt]
                (let [n (dom/node this "input-field")]
                  (.focus n)))]
    (dom/div nil
      (dom/input #js {:value    (or (prim/get-state this :v) "")
                      :onChange (gobj/get this "set-value")
                      :ref      "input-field"})
      (dom/button #js {:onClick focus} "Focus"))))

(defcard-fulcro fn-ref-test-1
  "# Tests that we can find the DOM component via a function ref."
  FunctionRefTest)

(defcard-fulcro str-ref-test-1
  "# Tests that we can find the DOM component via string ref.

  This card should work with inputs, but string refs are deprecated in React."
  StringRefTest)
