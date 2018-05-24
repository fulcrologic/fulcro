(ns fulcro.democards.react16-cards
  (:require [devcards.core :as dc]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro make-root]]
            [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.mutations :as m]))

(defsc D [this {:keys [n]}]
  {:ident         (fn [] [:X 1])
   :initial-state {:n 1}
   :query         [:n]}
  (dom/div nil
    (when (> n 10)
      (throw (ex-info "BOOM!" {})))
    (dom/button {:onClick #(m/set-integer! this :n :value (inc n))} (str n))))

(def ui-d (prim/factory D))

(defsc C [this props]
  {:initLocalState    {:ui-error false}
   :query             [{:ui/d (prim/get-query D)}]
   :initial-state     {:ui/d {}}
   :componentDidCatch (fn [error info]
                        (js/console.log :CATCH!!! error info)
                        (prim/set-state! this {:ui-error true}))}
  (let [error? (prim/get-state this :ui-error)]
    (dom/div
      (if error?
        (dom/div "Things went sideways")
        (ui-d props)))))

(defcard-fulcro test-card
  "
  ## React 16 Error Boundary Support

  NOTE: This card won't run with error boundaries unless compiled with React 16, even though defsc will accept the
  method signature.

  Under React 16, hitting the button past 10 will result in an alternate rendering because of the busted subcomponent.
  "
  C)
