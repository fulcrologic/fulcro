(ns com.fulcrologic.fulcro.cards.composition3-cards
  (:require
    [nubank.workspaces.model :as wsm]
    [nubank.workspaces.card-types.react :as ct.react]
    [nubank.workspaces.card-types.fulcro3 :as ct.fulcro]
    [nubank.workspaces.core :as ws]

    [cljs.core.async :as async]
    [com.fulcrologic.fulcro.dom :as dom :refer [div p input button h2 label]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.networking.mock-server-remote :refer [mock-http-server]]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.fulcro.algorithms.react-interop :as interop]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.wsscode.pathom.core :as p]
    [com.wsscode.pathom.connect :as pc]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.dom.events :as evt]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mock Server and database, in Fulcro client format for ease of use in demo
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce pretend-server-database
  (atom
    {:settings/id {1 {:settings/id         1
                      :settings/marketing? true
                      :settings/theme      :light-mode}}
     :user/id     {100 {:user/id       100
                        :user/name     "Emily"
                        :user/email    "emily@example.com"
                        :user/settings [:settings/id 1]}}}))

(pc/defresolver settings-resolver [_ {:settings/keys [id]}]
  {::pc/input  #{:settings/id}
   ::pc/output [:settings/marketing? :settings/theme]}
  (get-in @pretend-server-database [:settings/id id]))

(pc/defresolver user-resolver [_ {:user/keys [id]}]
  {::pc/input  #{:user/id}
   ::pc/output [:user/name :user/email :user/age {:user/settings [:settings/id]}]}
  (try
    (-> @pretend-server-database
      (get-in [:user/id id])
      (update :user/settings #(into {} [%])))
    (catch :default e
      (log/error e "Resolver fail"))))

(pc/defresolver current-user-resolver [_ _]
  {::pc/output [{:current-user [:user/id]}]}
  {:current-user {:user/id 100}})

(pc/defmutation server-update-user [_ {:user/keys [id name] :as params}]
  {::pc/sym    `update-user
   ::pc/output [:user/id]}
  (swap! pretend-server-database assoc-in [:user/id id :user/name] name)
  {:user/id id})

(pc/defmutation server-update-settings [_ {:settings/keys [id marketing?] :as params}]
  {::pc/sym    `update-settings
   ::pc/output [:settings/id]}
  (swap! pretend-server-database assoc-in [:settings/id id :settings/marketing?] marketing?)
  {:settings/id id})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Registration for the above resolvers and mutations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def resolvers [user-resolver current-user-resolver settings-resolver server-update-user
                server-update-settings])

(def pathom-parser (p/parser {::p/env     {::p/reader                 [p/map-reader
                                                                       pc/reader2
                                                                       pc/open-ident-reader]
                                           ::pc/mutation-join-globals [:tempids]}
                              ::p/mutate  pc/mutate
                              ::p/plugins [(pc/connect-plugin {::pc/register [resolvers]})
                                           (p/post-process-parser-plugin p/elide-not-found)
                                           p/error-handler-plugin]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Client. We close over the server parser above using a mock http server. The
;; extra level of indirection allows hot code reload to refresh the mock server and
;; parser without wiping out the app.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce raw-app
  (let [process-eql (fn [eql] (async/go
                                (pathom-parser {} eql)))
        app         (app/fulcro-app {:remotes {:remote (mock-http-server {:parser process-eql})}})]
    (inspect/app-started! app)
    app))

(m/defmutation bump [{:counter/keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:counter/id id :counter/n] inc)))

;; A kind of free-form widget we might want to use that needs no server I/O. We can generate
;; an ID for it, and GC it when done.
(defsc Counter [this props]
  {:query         [:counter/id :counter/n]
   :ident         :counter/id
   :initial-state {:counter/id :param/id
                   :counter/n  :param/n}
   ;; Optional. Std components will work fine.
   :use-hooks?    true}
  (button {:onClick #(comp/transact! this [(bump props)])}
    (str (:counter/n props))))

;; Important to use the right factory. This one establishes the stuff you need for nested Fulcro stuff to work
;; according to the book.
(def raw-counter (comp/factory Counter {:keyfn :counter/id}))

(m/defmutation toggle [{:item/keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:item/id id :item/complete?] not)))

;; A raw hooks component that uses a Fulcro sub-tree. See docstring on use-fulcro.
(defn Top [props]
  (comp/with-parent-context raw-app
    (let [counter (hooks/use-component raw-app Counter {:initial-params {:id 1 :n 42}
                                                        :initialize?    true
                                                        :keep-existing? true})]
      (div
        (raw-counter counter)))))

;; Render a truly raw react hooks component in a plain react card
(ws/defcard fulcro-composed-into-vanilla-react
  (ct.react/react-card
    (dom/create-element Top {})))

(def User (rc/nc [:user/id :user/name
                  {:user/settings
                   [:settings/id :settings/marketing?]}]))

(m/defmutation update-user [user-props]
  (action [{:keys [state]}]
    (swap! state update-in [:user/id (:user/id user-props)]
      merge user-props))
  (remote [env] (m/returning env User)))

(defn menu [{:menu/keys [current-tab]}]
  (let [set-tab! (fn [t] (m/raw-set-value! raw-app {:component/id ::menu} :menu/current-tab t))]
    (div :.ui.horizontal.pointing.menu
      (div :.item {:classes [(when (= current-tab :main) "active")]
                   :onClick (fn [] (set-tab! :main))} "Main")
      (div :.item {:classes [(when (= current-tab :settings) "active")]
                   :onClick (fn [] (set-tab! :settings))} "Settings"))))

(defn UserForm [_js-props]
  (let [{:user/keys [name] :as u} (hooks/use-root raw-app :current-user User {})]
    (div :.ui.segment
      (h2 "User")
      (div :.ui.form
        (div :.field
          (label "Name")
          (input {:value    (or name "")
                  :onBlur   (fn [evt] (comp/transact! raw-app [(update-user (select-keys u [:user/id :user/name]))]))
                  :onChange (fn [evt] (m/raw-set-value! raw-app u :user/name (evt/target-value evt)))}))))))

(def ui-user-form (interop/react-factory UserForm))

(m/defmutation update-settings [{:settings/keys [id marketing?] :as settings}]
  (action [{:keys [state]}]
    (swap! state update-in [:settings/id id] merge settings))
  (remote [_] (m/returning _ (rc/nc [:settings/id :settings/marketing?]))))

(defn SettingsForm [_js-props]
  (let [{:user/keys [settings]} (hooks/use-root raw-app :current-user User {})
        {:settings/keys [id marketing?]} settings]
    (div :.ui.segment
      (h2 "Settings")
      (div :.ui.form
        (div :.ui.checkbox
          (input {:type     "checkbox"
                  :onChange (fn [_] (comp/transact! raw-app [(update-settings {:settings/id         id
                                                                               :settings/marketing? (not marketing?)})]))
                  :checked  (boolean marketing?)})
          (label "Marketing Emails?"))))))

(def ui-settings-form (interop/react-factory SettingsForm))

(def MainMenu (rc/entity->component {:component/id ::menu :menu/current-tab :main}))

;; Raw hook that uses I/O
(defn RawReactWithFulcroIO [_]
  (hooks/use-lifecycle (fn [] (df/load! raw-app :current-user User)))
  (let [{:menu/keys [current-tab] :as menu-props} (hooks/use-root raw-app :root/menu
                                                    MainMenu
                                                    {:keep-existing? true
                                                     :initialize?    true})]
    (div
      (menu menu-props)
      (if (= current-tab :main)
        (ui-user-form)
        (ui-settings-form)))))

(ws/defcard fulcro-io-composed-in-raw-react
  {::wsm/align {:flex 1}}
  (ct.react/react-card
    (dom/create-element RawReactWithFulcroIO {})))

(comment
  (comp/has-ident?
    (raw/nc [:component/id :menu/current-tab]
      {:initial-state (fn [_] {:component/id ::menu :menu/current-tab :main})}))
  )