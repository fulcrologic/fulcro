(ns book.demos.loading-in-response-to-UI-routing
  (:require
    [fulcro.client.routing :as r]
    [fulcro.client.mutations :as m]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc InitialAppState initial-state]]
    [fulcro.client.data-fetch :as df]
    [fulcro.server :as server]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(server/defquery-root :all-settings
  "This is the only thing we wrote for the server...just return some value so we can see it really talked to the server for this query."
  (value [env params]
    [{:id 1 :value "Gorgon"}
     {:id 2 :value "Thraser"}
     {:id 3 :value "Under"}]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLIENT:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defsc SomeSetting [this {:keys [id value]}]
  {:query [:ui/fetch-state :id :value]
   :ident [:setting/by-id :id]}
  (dom/p nil "Setting " id " from server has value: " value))

(def ui-setting (prim/factory SomeSetting {:keyfn :id}))

(defsc SettingsTab [this {:keys [settings-content settings]}]
  {:initial-state {:kind             :settings
                   :settings-content "Settings Tab"
                   :settings         []}
   ; This query uses a "link"...a special ident with '_ as the ID. This indicates the item is at the database
   ; root, not inside of the "settings" database object. This is not needed as a matter of course...it is only used
   ; for convenience (since it is trivial to load something into the root of the database)
   :query         [:kind :settings-content {:settings (prim/get-query SomeSetting)}]}
  (dom/div nil
    settings-content
    (df/lazily-loaded (fn [] (map ui-setting settings)) settings)))

(def ui-settings-tab (prim/factory SettingsTab))

(defsc MainTab [this {:keys [main-content]}]
  {:initial-state {:kind :main :main-content "Main Tab"}
   :query         [:kind :main-content]}
  (dom/div nil main-content))

(def ui-main-tab (prim/factory MainTab))

(r/defrouter UITabs :ui-router
  (ident [this {:keys [kind]}] [kind :tab])
  :main MainTab
  :settings SettingsTab)

(def ui-tabs (prim/factory UITabs))

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

(defsc Root [this {:keys [ui/react-key current-tab] :as props}]
  ; Construction MUST compose to root, just like the query. The resulting tree will automatically be normalized into the
  ; app state graph database.
  {:initial-state (fn [params] {:ui/react-key "initial" :current-tab (prim/get-initial-state UITabs nil)})
   :query         [:ui/react-key {:current-tab (prim/get-query UITabs)}]}
  (dom/div #js {:key react-key}
    ; The selection of tabs can be rendered in a child, but the transact! must be done from the parent (to
    ; ensure proper re-render of the tab body). See prim/computed for passing callbacks.
    (dom/button #js {:onClick #(prim/transact! this `[(choose-tab {:tab :main})])} "Main")
    (dom/button #js {:onClick #(prim/transact! this `[(choose-tab {:tab :settings})
                                                      ; extra mutation: sample of what you would do to lazy load the tab content
                                                      (lazy-load-tab {:tab :settings})])} "Settings")
    (ui-tabs current-tab)))



