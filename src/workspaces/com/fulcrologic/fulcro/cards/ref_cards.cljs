(ns com.fulcrologic.fulcro.cards.ref-cards
  (:require
    ["react" :as react]
    [nubank.workspaces.card-types.fulcro3 :as ct.fulcro]
    [nubank.workspaces.core :as ws]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div input]]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.dom.events :as evt]))

(defsc CustomInput [this {:keys [forward-ref label value onChange] :as props}]
  {}
  (dom/div :.ui.field
    (input (cond-> {:name  (str label)
                    :value (str value)}
             forward-ref (assoc :ref forward-ref)
             onChange (assoc :onChange onChange)))
    (dom/label {:htmlFor (str label)} label)))

(def ui-custom-input (comp/factory CustomInput))

(defsc HooksUI [this props]
  {:use-hooks? true}
  (let [[v setv!] (hooks/use-state "")
        input-ref (hooks/use-ref nil)]
    (hooks/use-effect
      (fn []
        (let [input (.-current input-ref)]
          (when input
            (js/console.log input)
            (.focus input)))

        (fn []))
      [(.-current input-ref)])
    (div
      (dom/h4 "My Form")
      (ui-custom-input
        {:label       "My Input"
         :value       v
         :forward-ref input-ref
         :onChange    (fn [v] (setv! v))}))))

(ws/defcard ref-hooks-demo-card
  (ct.fulcro/fulcro-card
    {::ct.fulcro/wrap-root? false
     ::ct.fulcro/root       HooksUI}))

(defsc StdUI [^js this {:keys [value]}]
  {:query             [:id :value]
   :ident             :id
   :initLocalState    (fn [^js this props] (set! (.-inputref this) (react/createRef)))
   :initial-state     {:id    42
                       :value "Bob"}
   :componentDidMount (fn [^js this]
                        (let [input-ref (.-current (.-inputref this))]
                          (when input-ref
                            (.focus input-ref))))}
  (let [input-ref (.-inputref this)]
    (div
      (dom/h4 "My Form")
      (ui-custom-input
        {:label       "My Input"
         :value       value
         :forward-ref input-ref
         :onChange    (fn [v] (m/set-string!! this :value :value (evt/target-value v)))}))))

(ws/defcard ref-demo-card
  (ct.fulcro/fulcro-card
    {::ct.fulcro/wrap-root? true
     ::ct.fulcro/root       StdUI}))
