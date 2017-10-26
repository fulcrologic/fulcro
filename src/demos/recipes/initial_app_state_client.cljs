(ns recipes.initial-app-state-client
  (:require
    [fulcro.client.core :as fc :refer [InitialAppState initial-state]]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.mutations :as m]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as om :refer [defui]]))

(defonce app (atom (fc/new-fulcro-client)))

(defmethod m/mutate 'nav/settings [{:keys [state]} sym params]
  {:action (fn [] (swap! state assoc :panes [:settings :singleton]))})

(defmethod m/mutate 'nav/main [{:keys [state]} sym params]
  {:action (fn [] (swap! state assoc :panes [:main :singleton]))})

(defui ItemLabel
  static InitialAppState
  (initial-state [clz {:keys [value]}] {:value value})
  static om/IQuery
  (query [this] [:value])
  static om/Ident
  (ident [this {:keys [value]}] [:labels/by-value value])
  Object
  (render [this]
    (let [{:keys [value]} (om/props this)]
      (dom/p nil value))))

(def ui-label (om/factory ItemLabel {:keyfn :value}))

;; Foo and Bar are elements of a mutli-type to-many union relation (each leaf can be a Foo or a Bar). We use params to
;; allow initial state to put more than one in place and have them be unique.
(defui Foo
  static InitialAppState
  (initial-state [clz {:keys [id label]}] {:id id :type :foo :label (initial-state ItemLabel {:value label})})
  static om/IQuery
  (query [this] [:type :id {:label (om/get-query ItemLabel)}])
  Object
  (render [this]
    (let [{:keys [label]} (om/props this)]
      (dom/div nil
        (dom/h2 nil "Foo")
        (ui-label label)))))

(def ui-foo (om/factory Foo {:keyfn :id}))

(defui Bar
  static InitialAppState
  (initial-state [clz {:keys [id label]}] {:id id :type :bar :label (initial-state ItemLabel {:value label})})
  static om/IQuery
  (query [this] [:type :id {:label (om/get-query ItemLabel)}])
  Object
  (render [this]
    (let [{:keys [label]} (om/props this)]
      (dom/div nil
        (dom/h2 nil "Bar")
        (ui-label label)))))

(def ui-bar (om/factory Bar {:keyfn :id}))

;; This is the to-many union component. It is the decision maker (it has no state or rendering of it's own)
;; The initial state of this component is the to-many (vector) value of various children
;; The render just determines which thing it is, and passes on the that renderer
(defui ListItem
  static InitialAppState
  (initial-state [clz params] [(initial-state Bar {:id 1 :label "A"}) (initial-state Foo {:id 2 :label "B"}) (initial-state Bar {:id 3 :label "C"})])
  static om/IQuery
  (query [this] {:foo (om/get-query Foo) :bar (om/get-query Bar)})
  static om/Ident
  (ident [this props] [(:type props) (:id props)])
  Object
  (render [this]
    (let [{:keys [type] :as props} (om/props this)]
      (case type
        :foo (ui-foo props)
        :bar (ui-bar props)
        (dom/p nil "No Item renderer!")))))

(def ui-list-item (om/factory ListItem {:keyfn :id}))

;; Settings and Main are the target "Panes" of a to-one union (e.g. imagine tabs...we use buttons as the tab switching in
;; this example). The initial state looks very much like any other component, as does the rendering.
(defui ^:once Settings
  static InitialAppState
  (initial-state [clz params] {:type :settings :id :singleton :label (initial-state ItemLabel {:value "Settings"})})
  static om/IQuery
  (query [this] [:type :id {:label (om/get-query ItemLabel)}])
  Object
  (render [this]
    (let [{:keys [label]} (om/props this)]
      (ui-label label))))

(def ui-settings (om/factory Settings {:keyfn :type}))

(defui ^:once Main
  static InitialAppState
  (initial-state [clz params] {:type :main :id :singleton :label (initial-state ItemLabel {:value "Main"})})
  static om/IQuery
  (query [this] [:type :id {:label (om/get-query ItemLabel)}])
  Object
  (render [this]
    (let [{:keys [label]} (om/props this)]
      (ui-label label))))

(def ui-main (om/factory Main {:keyfn :type}))

;; This is a to-one union component. Again, it has no state of its own or rendering. The initial state is the single
;; child that should appear. Fulcro (during startup) will detect this component, and then use the query to figure out
;; what other children (the ones that have initial-state defined) should be placed into app state.
(defui ^:once PaneSwitcher
  static InitialAppState
  (initial-state [clz params] (initial-state Main nil))
  static om/IQuery
  (query [this] {:settings (om/get-query Settings) :main (om/get-query Main)})
  static om/Ident
  (ident [this props] [(:type props) (:id props)])
  Object
  (render [this]
    (let [{:keys [type] :as props} (om/props this)]
      (case type
        :settings (ui-settings props)
        :main (ui-main props)
        (dom/p nil "NO PANE!")))))

(def ui-panes (om/factory PaneSwitcher {:keyfn :type}))

;; The root. Everything just composes to here (state and query)
;; Note, in core (where we create the app) there is no need to say anything about initial state!
(defui ^:once Root
  static InitialAppState
  (initial-state [clz params] {:ui/react-key "abc"
                               :panes        (initial-state PaneSwitcher nil)
                               :items        (initial-state ListItem nil)})
  static om/IQuery
  (query [this] [:ui/react-key
                 {:items (om/get-query ListItem)}
                 {:panes (om/get-query PaneSwitcher)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key panes items]} (om/props this)]
      (dom/div #js {:key react-key}
        (dom/button #js {:onClick (fn [evt] (om/transact! this '[(nav/settings)]))} "Go to settings")
        (dom/button #js {:onClick (fn [evt] (om/transact! this '[(nav/main)]))} "Go to main")

        (ui-panes panes)

        (dom/h1 nil "Heterogenous list:")

        (dom/ul nil
          (mapv ui-list-item items))))))

