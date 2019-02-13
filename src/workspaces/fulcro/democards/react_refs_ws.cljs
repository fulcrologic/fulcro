(ns fulcro.democards.react-refs-ws
  (:require
    [nubank.workspaces.core :as ws]
    [nubank.workspaces.model :as wsm]
    [nubank.workspaces.card-types.fulcro :as ct.fulcro]
    [nubank.workspaces.lib.fulcro-portal :as f.portal]
    [fulcro.client.dom :as dom]
    [goog.object :as gobj]
    [fulcro.client.primitives :as prim :refer [defui defsc InitialAppState initial-state]]))

(defsc Root [this props]
  (dom/div "TODO"))

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
    (dom/div
      (dom/input {:value    (or (prim/get-state this :v) "")
                  :onChange (gobj/get this "set-value")
                  :ref      (gobj/get this "set-ref")})
      (dom/button {:onClick focus} "Focus"))))

(defsc StringRefTest [this props]
  {:componentWillMount (fn []
                         (gobj/set this "set-value" (fn [evt] (prim/set-state! this {:v (.. evt -target -value)}))))}
  (let [focus (fn [evt]
                (let [n (dom/node this "input-field")]
                  (.focus n)))]
    (dom/div
      (dom/input {:value    (or (prim/get-state this :v) "")
                  :onChange (gobj/get this "set-value")
                  :ref      "input-field"})
      (dom/button {:onClick focus} "Focus"))))

#_"# Tests that we can find the DOM component via a function ref."
(ws/defcard fn-ref-test
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root FunctionRefTest}))

#_"# Tests that we can find the DOM component via string ref.

  This card should work with inputs, but string refs are deprecated in React."
(ws/defcard str-ref-test
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root StringRefTest}))
