(ns cards.initial-app-state
  (:require
    [devcards.core :as dc :include-macros true]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [fulcro.client.dom :as dom]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
    [fulcro.client :as fc ]
    [fulcro.client.mutations :as m]
    [fulcro.client.primitives :as prim :refer [defui defsc InitialAppState initial-state]]))

(defonce app (atom (fc/new-fulcro-client)))

(defmethod m/mutate 'nav/settings [{:keys [state]} sym params]
  {:action (fn [] (swap! state assoc :panes [:settings :singleton]))})

(defmethod m/mutate 'nav/main [{:keys [state]} sym params]
  {:action (fn [] (swap! state assoc :panes [:main :singleton]))})

(defui ItemLabel
  static InitialAppState
  (initial-state [clz {:keys [value]}] {:value value})
  static prim/IQuery
  (query [this] [:value])
  static prim/Ident
  (ident [this {:keys [value]}] [:labels/by-value value])
  Object
  (render [this]
    (let [{:keys [value]} (prim/props this)]
      (dom/p nil value))))

(def ui-label (prim/factory ItemLabel {:keyfn :value}))

;; Foo and Bar are elements of a mutli-type to-many union relation (each leaf can be a Foo or a Bar). We use params to
;; allow initial state to put more than one in place and have them be unique.
(defui Foo
  static InitialAppState
  (initial-state [clz {:keys [id label]}] {:id id :type :foo :label (initial-state ItemLabel {:value label})})
  static prim/IQuery
  (query [this] [:type :id {:label (prim/get-query ItemLabel)}])
  Object
  (render [this]
    (let [{:keys [label]} (prim/props this)]
      (dom/div nil
        (dom/h2 nil "Foo")
        (ui-label label)))))

(def ui-foo (prim/factory Foo {:keyfn :id}))

(defui Bar
  static InitialAppState
  (initial-state [clz {:keys [id label]}] {:id id :type :bar :label (initial-state ItemLabel {:value label})})
  static prim/IQuery
  (query [this] [:type :id {:label (prim/get-query ItemLabel)}])
  Object
  (render [this]
    (let [{:keys [label]} (prim/props this)]
      (dom/div nil
        (dom/h2 nil "Bar")
        (ui-label label)))))

(def ui-bar (prim/factory Bar {:keyfn :id}))

;; This is the to-many union component. It is the decision maker (it has no state or rendering of it's own)
;; The initial state of this component is the to-many (vector) value of various children
;; The render just determines which thing it is, and passes on the that renderer
(defui ListItem
  static InitialAppState
  (initial-state [clz params] [(initial-state Bar {:id 1 :label "A"}) (initial-state Foo {:id 2 :label "B"}) (initial-state Bar {:id 3 :label "C"})])
  static prim/IQuery
  (query [this] {:foo (prim/get-query Foo) :bar (prim/get-query Bar)})
  static prim/Ident
  (ident [this props] [(:type props) (:id props)])
  Object
  (render [this]
    (let [{:keys [type] :as props} (prim/props this)]
      (case type
        :foo (ui-foo props)
        :bar (ui-bar props)
        (dom/p nil "No Item renderer!")))))

(def ui-list-item (prim/factory ListItem {:keyfn :id}))

;; Settings and Main are the target "Panes" of a to-one union (e.g. imagine tabs...we use buttons as the tab switching in
;; this example). The initial state looks very much like any other component, as does the rendering.
(defui ^:once Settings
  static InitialAppState
  (initial-state [clz params] {:type :settings :id :singleton :label (initial-state ItemLabel {:value "Settings"})})
  static prim/IQuery
  (query [this] [:type :id {:label (prim/get-query ItemLabel)}])
  Object
  (render [this]
    (let [{:keys [label]} (prim/props this)]
      (ui-label label))))

(def ui-settings (prim/factory Settings {:keyfn :type}))

(defui ^:once Main
  static InitialAppState
  (initial-state [clz params] {:type :main :id :singleton :label (initial-state ItemLabel {:value "Main"})})
  static prim/IQuery
  (query [this] [:type :id {:label (prim/get-query ItemLabel)}])
  Object
  (render [this]
    (let [{:keys [label]} (prim/props this)]
      (ui-label label))))

(def ui-main (prim/factory Main {:keyfn :type}))

;; This is a to-one union component. Again, it has no state of its own or rendering. The initial state is the single
;; child that should appear. Fulcro (during startup) will detect this component, and then use the query to figure out
;; what other children (the ones that have initial-state defined) should be placed into app state.
(defui ^:once PaneSwitcher
  static InitialAppState
  (initial-state [clz params] (initial-state Main nil))
  static prim/IQuery
  (query [this] {:settings (prim/get-query Settings) :main (prim/get-query Main)})
  static prim/Ident
  (ident [this props] [(:type props) (:id props)])
  Object
  (render [this]
    (let [{:keys [type] :as props} (prim/props this)]
      (case type
        :settings (ui-settings props)
        :main (ui-main props)
        (dom/p nil "NO PANE!")))))

(def ui-panes (prim/factory PaneSwitcher {:keyfn :type}))

;; The root. Everything just composes to here (state and query)
;; Note, in core (where we create the app) there is no need to say anything about initial state!
(defui ^:once Root
  static InitialAppState
  (initial-state [clz params] {:ui/react-key "abc"
                               :panes        (initial-state PaneSwitcher nil)
                               :items        (initial-state ListItem nil)})
  static prim/IQuery
  (query [this] [:ui/react-key
                 {:items (prim/get-query ListItem)}
                 {:panes (prim/get-query PaneSwitcher)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key panes items]} (prim/props this)]
      (dom/div #js {:key react-key}
        (dom/button #js {:onClick (fn [evt] (prim/transact! this '[(nav/settings)]))} "Go to settings")
        (dom/button #js {:onClick (fn [evt] (prim/transact! this '[(nav/main)]))} "Go to main")

        (ui-panes panes)

        (dom/h1 nil "Heterogenous list:")

        (dom/ul nil
          (mapv ui-list-item items))))))

(dc/defcard-doc
  "# Initial State

  Fulcro's initial state support allows you to compose the initial (startup) state using the components themselves.
  This allows you to co-locate the component initial state for local reasoning, and compose children into
  parents so that any component in the app can be easily relocated. If such components also have an ident, any
  mutations need to interact with those components will automatically just work, since you'll be working on
  normalized data!

  The source of the demo components is:
  "
  (dc/mkdn-pprint-source Main)
  (dc/mkdn-pprint-source Settings)
  (dc/mkdn-pprint-source PaneSwitcher)
  (dc/mkdn-pprint-source ItemLabel)
  (dc/mkdn-pprint-source Foo)
  (dc/mkdn-pprint-source Bar)
  (dc/mkdn-pprint-source ListItem)
  (dc/mkdn-pprint-source Root))

(defcard-fulcro initial-state
  "
  Note: There are two union queries in this application. Notice how the initial app state manages to find them all even
  though one of them is not in the initial tree of initial state (PaneSwitcher composes in Main, but Settings is
  auto-found and added as well).
  "
  Root
  {}
  {:inspect-data true})
