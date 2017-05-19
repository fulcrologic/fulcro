(ns recipes.tabbed-interface-client
  (:require
    [om.dom :as dom]
    [om.next :as om :refer [defui]]
    [untangled.client.mutations :as m]
    [untangled.client.core :as uc :refer [InitialAppState initial-state]]
    [untangled.client.data-fetch :as df]))

(defui ^:once SomeSetting
  static om/IQuery
  (query [this] [:ui/fetch-state :id :value])
  static om/Ident
  (ident [this props] [:setting/by-id (:id props)])
  Object
  (render [this]
    (let [{:keys [id value]} (om/props this)]
      (dom/p nil "Setting " id " from server has value: " value))))

(def ui-setting (om/factory SomeSetting {:keyfn :id}))

(defui ^:once SettingsTab
  static InitialAppState
  (initial-state [clz params] {:id               :tab
                               :which-tab        :settings
                               :settings-content "Settings Tab"
                               :settings         []})
  static om/IQuery
  ; This query uses a "link"...a special ident with '_ as the ID. This indicates the item is at the database
  ; root, not inside of the "settings" database object. This is not needed as a matter of course...it is only used
  ; for convenience (since it is trivial to load something into the root of the database)
  (query [this] [:which-tab :settings-content {:settings (om/get-query SomeSetting)}])
  Object
  (render [this]
    (let [{:keys [settings-content settings]} (om/props this)]
      (dom/div nil
        settings-content
        (df/lazily-loaded (fn [] (map ui-setting settings)) settings)))))

(def ui-settings-tab (om/factory SettingsTab))

(defui ^:once MainTab
  static InitialAppState
  (initial-state [clz params] {:id :tab :which-tab :main :main-content "Main Tab"})
  static om/IQuery
  (query [this] [:which-tab :main-content])
  Object
  (render [this]
    (let [{:keys [main-content]} (om/props this)]
      (dom/div nil main-content))))

(def ui-main-tab (om/factory MainTab))

; This is the main trick: a component that serves to pick which UI/query to render based on which thing
; the ident of :current-tab points to in the database. The :current-tab name means nothing, of course, it
; was just a convenient name (see Root, which queried for it and passed the result here).
; IMPORTANT:
; 1. query must be a union, which is a map keyed by "object type" with the query to use for that object
; 2. The ident MUST derive the correct db ident for whatever you got in props (which will come from the child)
; 3. You are responsible for rendering the correct UI from this render to properly render the child
(defui ^:once TabUnion
  ;; InitialAppState can only initialize one of these (it is a to-one relation). See tabbed-interface.core for the trick to initialize other tabs
  ; IMPORTANT NOTE: We're using the state of **A** specific child. This is because a union controlling component has no state
  ; of it's own.
  static InitialAppState
  (initial-state [clz params] (initial-state MainTab nil))
  static om/IQuery
  (query [this] {:main (om/get-query MainTab) :settings (om/get-query SettingsTab)})
  static om/Ident
  (ident [this props] [(:which-tab props) :tab])
  Object
  (render [this]
    (let [{:keys [which-tab] :as props} (om/props this)]
      (dom/div nil
        (case which-tab
          :main (ui-main-tab props)                         ; note props are just passed straight through
          :settings (ui-settings-tab props)
          (dom/p nil "Missing tab!"))))))

(def ui-tabs (om/factory TabUnion))

(defui ^:once Root
  ; Construction MUST compose to root, just like the query. The resulting tree will automatically be normalized into the
  ; app state graph database.
  static InitialAppState
  (initial-state [clz params] {:ui/react-key "initial" :current-tab (initial-state TabUnion nil)})
  static om/IQuery
  (query [this] [:ui/react-key {:current-tab (om/get-query TabUnion)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key current-tab] :as props} (om/props this)]
      (dom/div #js {:key react-key}
        ; The selection of tabs can be rendered in a child, but the transact! must be done from the parent (to
        ; ensure proper re-render of the tab body). See om/computed for passing callbacks.
        (dom/ul nil
          (dom/li #js {:onClick #(om/transact! this '[(app/choose-tab {:tab :main})])} "Main")
          (dom/li #js {:onClick #(om/transact! this '[(app/choose-tab {:tab :settings})
                                                      ; extra mutation: sample of what you would do to lazy load the tab content
                                                      (app/lazy-load-tab {:tab :settings})])} "Settings"))
        (ui-tabs current-tab)))))

;; This is all you need to "change tabs"
(defmethod m/mutate 'app/choose-tab [{:keys [state]} k {:keys [tab]}]
  {:action (fn [] (swap! state assoc-in [:current-tab 0] tab))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LAZY LOADING TAB CONTENT
;; This is the shape of what to do. We define a method that can examine the
;; state to decide if we want to trigger a load. Then we define a mutation
;; that the UI can call during transact (see the transact! call for Settings on Root in ui.cljs).
;; The mutation itself (app/lazy-load-tab) below uses a data-fetch helper function to
;; set :remote to the right thing, and can then give one or more load-data-action's to
;; indicate what should actually be retrieved. The server implementation is trivial in
;; this case. See api.clj.

;; When to consider the data missing? Check the state and find out.
(defn missing-tab? [state tab] (empty? (-> @state :settings :tab :settings)))

(defmethod m/mutate 'app/lazy-load-tab [{:keys [state] :as env} k {:keys [tab]}]
  (when (missing-tab? state tab)
    ; remote must be the value returned by data-fetch remote-load on your parsing environment.
    {:remote (df/remote-load env)
     :action (fn []
               ; Specify what you want to load as one or more calls to load-action (each call adds an item to load):
               (df/load-action state :all-settings SomeSetting
                 {:target  [:settings :tab :settings]
                  :refresh [:settings]})
               ; anything else you need to do for this transaction
               )}))

(defonce app (atom (uc/new-untangled-client)))
