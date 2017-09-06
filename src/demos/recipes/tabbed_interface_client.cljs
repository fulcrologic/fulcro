(ns recipes.tabbed-interface-client
  (:require
    [om.dom :as dom]
    [om.next :as om :refer [defui]]
    [fulcro.client.routing :as r]
    [fulcro.client.mutations :as m]
    [fulcro.client.core :as fc :refer [InitialAppState initial-state]]
    [fulcro.client.data-fetch :as df]))

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
  (initial-state [clz params] {:kind             :settings
                               :settings-content "Settings Tab"
                               :settings         []})
  static om/IQuery
  ; This query uses a "link"...a special ident with '_ as the ID. This indicates the item is at the database
  ; root, not inside of the "settings" database object. This is not needed as a matter of course...it is only used
  ; for convenience (since it is trivial to load something into the root of the database)
  (query [this] [:kind :settings-content {:settings (om/get-query SomeSetting)}])
  Object
  (render [this]
    (let [{:keys [settings-content settings]} (om/props this)]
      (dom/div nil
        settings-content
        (df/lazily-loaded (fn [] (map ui-setting settings)) settings)))))

(def ui-settings-tab (om/factory SettingsTab))

(defui ^:once MainTab
  static InitialAppState
  (initial-state [clz params] {:kind :main :main-content "Main Tab"})
  static om/IQuery
  (query [this] [:kind :main-content])
  Object
  (render [this]
    (let [{:keys [main-content]} (om/props this)]
      (dom/div nil main-content))))

(def ui-main-tab (om/factory MainTab))

(r/defrouter UITabs :ui-router
  (ident [this {:keys [kind]}] [kind :tab])
  :main MainTab
  :settings SettingsTab)

(def ui-tabs (om/factory UITabs))

(m/defmutation choose-tab [{:keys [tab]}]
  (action [{:keys [state]}] (swap! state r/set-route :ui-router [tab :tab])))

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
(defn missing-tab? [state tab]
  (let [settings (-> @state :settings :tab :settings)]
    (or (not (vector? settings))
      (and (vector? settings) (empty? settings)))))

(m/defmutation lazy-load-tab [{:keys [tab]}]
  (action [{:keys [state] :as env}]
    ; Specify what you want to load as one or more calls to load-action (each call adds an item to load):
    (when (missing-tab? state tab)
      (df/load-action env :all-settings SomeSetting
        {:target  [:settings :tab :settings]
         :refresh [:settings]})))
  (remote [{:keys [state] :as env}]
    (df/remote-load env)))

(defui ^:once Root
  ; Construction MUST compose to root, just like the query. The resulting tree will automatically be normalized into the
  ; app state graph database.
  static InitialAppState
  (initial-state [clz params] {:ui/react-key "initial" :current-tab (initial-state UITabs nil)})
  static om/IQuery
  (query [this] [:ui/react-key {:current-tab (om/get-query UITabs)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key current-tab] :as props} (om/props this)]
      (dom/div #js {:key react-key}
        ; The selection of tabs can be rendered in a child, but the transact! must be done from the parent (to
        ; ensure proper re-render of the tab body). See om/computed for passing callbacks.
        (dom/button #js {:onClick #(om/transact! this `[(choose-tab {:tab :main})])} "Main")
        (dom/button #js {:onClick #(om/transact! this `[(choose-tab {:tab :settings})
                                                        ; extra mutation: sample of what you would do to lazy load the tab content
                                                        (lazy-load-tab {:tab :settings})])} "Settings")
        (ui-tabs current-tab)))))


