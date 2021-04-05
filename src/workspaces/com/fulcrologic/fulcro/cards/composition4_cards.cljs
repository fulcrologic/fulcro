(ns com.fulcrologic.fulcro.cards.composition4-cards
  (:require
    [clojure.pprint :refer [pprint]]
    [nubank.workspaces.model :as wsm]
    [nubank.workspaces.card-types.react :as ct.react]
    [nubank.workspaces.card-types.fulcro3 :as ct.fulcro]
    [nubank.workspaces.core :as ws]

    [cljs.core.async :as async]
    [com.fulcrologic.fulcro.dom :as dom :refer [div p input button h2 label]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.networking.mock-server-remote :refer [mock-http-server]]
    [com.fulcrologic.fulcro.alpha.raw-components3 :as rc3]
    [com.fulcrologic.fulcro.alpha.raw :as raw]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.fulcro.algorithms.react-interop :as interop]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.wsscode.pathom.core :as p]
    [com.wsscode.pathom.connect :as pc]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.algorithms.data-targeting :as dt]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mock Server and database, in Fulcro client format for ease of use in demo
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defonce pretend-server-database
  (atom
    {:settings/id {1 {:settings/id         1
                      :settings/marketing? true
                      :settings/theme      :light-mode}}
     :account/id  {1000 {:account/id       1000
                         :account/email    "bob@example.com"
                         :account/password "letmein"}}
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

(pc/defmutation server-save-form [_ form-diff]
  {::pc/sym `save-form}
  (swap! pretend-server-database
    (fn [s]
      (reduce-kv
        (fn [final-state ident changes]
          (reduce-kv
            (fn [fs k {:keys [after]}]
              (if (nil? after)
                (update-in fs ident dissoc k)
                (assoc-in fs (conj ident k) after)))
            final-state
            changes))
        s
        form-diff)))
  (log/info "Updated server to:" (with-out-str (pprint @pretend-server-database)))
  nil)

;; For the UISM DEMO
(defonce session-id (atom 1000))                            ; pretend like we have server state to remember client

(pc/defresolver account-resolver [_ {:account/keys [id]}]
  {::pc/input  #{:account/id}
   ::pc/output [:account/email]}
  (select-keys (get-in @pretend-server-database [:account/id id] {}) [:account/email]))

(pc/defresolver session-resolver [_ {:account/keys [id]}]
  {::pc/output [{:current-session [:account/id]}]}
  (if @session-id
    {:current-session {:account/id @session-id}}
    {:current-session {:account/id :none}}))

(pc/defmutation server-login [_ {:keys [email password]}]
  {::pc/sym    `login
   ::pc/output [:account/id]}
  (let [accounts (vals (get @pretend-server-database :account/id))
        account  (first
                   (filter
                     (fn [a] (and (= password (:account/password a)) (= email (:account/email a))))
                     accounts))]
    (when (log/spy :info "Found account" account)
      (reset! session-id (:account/id account))
      account)))

(pc/defmutation server-logout [_ _]
  {::pc/sym `logout}
  (reset! session-id nil))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Registration for the above resolvers and mutations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def resolvers [user-resolver current-user-resolver settings-resolver server-save-form account-resolver session-resolver
                server-login server-logout])

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
                                (let [tm (async/timeout 300)]
                                  (async/<! tm)
                                  (pathom-parser {} eql))))
        app         (app/fulcro-app {:remotes {:remote (mock-http-server {:parser process-eql})}})]
    (inspect/app-started! app)
    app))

;; TODO: Write `raw/formc` that makes a form automatically with form config join and form fields on everything that isn't an id
#_#_(def Settings (raw/nc [:settings/id :settings/marketing? fs/form-config-join] {:form-fields #{:settings/marketing?}}))
    (def User (raw/nc [:user/id :user/name fs/form-config-join {:user/settings (comp/get-query Settings)}] {:form-fields #{:user/name
                                                                                                                           :user/settings}}))

(def User (raw/formc [:ui/saving? [df/marker-table '_]
                      :user/id :user/name
                      {:user/settings [:settings/id :settings/marketing?]}]))

(comment
  (comp/component-options User)
  (comp/get-ident User {:user/id 34})
  (comp/get-initial-state User {})
  (comp/get-ident User {:user/id 34})
  (-> (comp/get-query User) (meta) (:component) (comp/get-query))
  )

(m/defmutation initialize-form [_]
  (action [{:keys [state]}]
    (let [ident (get @state :current-user)]
      (fns/swap!-> state
        (fs/add-form-config* User ident {:destructive? true})
        (fs/mark-complete* ident)))))

(defn- set-saving! [{:keys [state ref]} tf]
  (when (vector? ref)
    (swap! state assoc-in (conj ref :ui/saving?) tf)))

(m/defmutation save-form [form]
  (action [{:keys [state] :as env}]
    (set-saving! env true)
    (let [idk (raw/id-key form)
          id  (get form idk)]
      (when (and idk id)
        (swap! state fs/entity->pristine* [idk id]))))
  (ok-action [env] (set-saving! env false))
  (error-action [env] (set-saving! env false))
  (remote [env]
    (-> env
      (m/with-params (fs/dirty-fields form true))
      (m/returning User))))

(defn UserForm [_js-props]
  (hooks/use-lifecycle (fn [] (df/load! raw-app :current-user User {:post-mutation `initialize-form
                                                                    :marker        ::user})))
  (let [{:ui/keys   [saving?]
         :user/keys [id name settings] :as u} (rc3/use-root raw-app :current-user User {})
        loading? (df/loading? (get-in u [df/marker-table ::user]))]
    (div :.ui.segment
      (h2 "Form")
      (div :.ui.form {:classes [(when loading? "loading")]}
        (div :.field
          (label "Name")
          (input {:value    (or name "")
                  :onChange (fn [evt] (raw/set-value!! raw-app u :user/name (evt/target-value evt)))}))
        (let [{:settings/keys [marketing?]} settings]
          (div :.ui.checkbox
            (input {:type     "checkbox"
                    :onChange (fn [_] (raw/update-value!! raw-app settings :settings/marketing? not))
                    :checked  (boolean marketing?)})
            (label "Marketing Emails?")))
        (div :.field
          (dom/button :.ui.primary.button
            {:classes [(when-not (fs/dirty? u) "disabled")
                       (when saving? "loading")]
             :onClick (fn [] (comp/transact! raw-app [(save-form u)] {:ref [:user/id id]}))}
            "Save"))))))

(def ui-user-form (interop/react-factory UserForm))

(defn RawReactWithFulcroIO [_] (ui-user-form))

(ws/defcard fulcro-io-composed-in-raw-react
  {::wsm/align {:flex 1}}
  (ct.react/react-card
    (dom/create-element RawReactWithFulcroIO {})))

(def global-events {:event/unmounted {::uism/handler (fn [env] env)}})

(uism/defstatemachine session-machine
  {::uism/actor-names
   #{:actor/login-form :actor/current-account}

   ::uism/aliases
   {:email    [:actor/login-form :email]
    :password [:actor/login-form :password]
    :failed?  [:actor/login-form :failed?]
    :name     [:actor/current-account :account/email]}

   ::uism/states
   {:initial
    {::uism/handler (fn [env]
                      (let [LoginForm (raw/nc [:component/id :email :password :failed?] {:componentName ::LoginForm})
                            Session   (raw/nc [:account/id :account/email] {:componentName ::Session})]
                        (log/info "initial handler" (::uism/event-id env))
                        (-> env
                          (uism/apply-action assoc-in [:account/id :none] {:account/id :none})
                          (uism/apply-action assoc-in [:component/id ::LoginForm] {:component/id ::LoginForm :email "" :password "" :failed? false})
                          (uism/reset-actor-ident :actor/current-account (uism/with-actor-class [:account/id :none] Session))
                          (uism/reset-actor-ident :actor/login-form (uism/with-actor-class [:component/id ::LoginForm] LoginForm))
                          (uism/load :current-session :actor/current-account {::uism/ok-event    :event/done
                                                                              ::uism/error-event :event/done})
                          (uism/activate :state/checking-session))))}

    :state/checking-session
    {::uism/events
     (merge global-events
       {:event/done       {::uism/handler
                           (fn [{::uism/keys [state-map] :as env}]
                             (let [id (log/spy :info (some-> state-map :current-session second))]
                               (cond-> env
                                 (pos-int? id) (->
                                                 (uism/reset-actor-ident :actor/current-account [:account/id id])
                                                 (uism/activate :state/logged-in))
                                 (not (pos-int? id)) (uism/activate :state/gathering-credentials))))}
        :event/post-login {::uism/handler
                           (fn [{::uism/keys [state-map] :as env}]
                             (let [session-ident (get state-map :current-session)
                                   Session       (uism/actor-class env :actor/current-account)
                                   logged-in?    (pos-int? (second session-ident))]
                               (if logged-in?
                                 (-> env
                                   (uism/reset-actor-ident :actor/current-account (uism/with-actor-class session-ident Session))
                                   (uism/activate :state/logged-in))
                                 (-> env
                                   (uism/assoc-aliased :failed? true)
                                   (uism/activate :state/gathering-credentials)))))}})}

    :state/gathering-credentials
    {::uism/events
     (merge global-events
       {:event/login {::uism/handler
                      (fn [env]
                        (-> env
                          (uism/assoc-aliased :failed? false)
                          (uism/trigger-remote-mutation :actor/login-form `login {:email             (uism/alias-value env :email)
                                                                                  :password          (uism/alias-value env :password)
                                                                                  ::m/returning      (uism/actor-class env :actor/current-account)
                                                                                  ::dt/target        [:current-session]
                                                                                  ::uism/ok-event    :event/post-login
                                                                                  ::uism/error-event :event/post-login})
                          (uism/activate :state/checking-session)))}})}

    :state/logged-in
    {::uism/events
     (merge global-events
       {:event/logout {::uism/handler
                       (fn [env]
                         (let [Session (uism/actor-class env :actor/current-account)]
                           (-> env
                             (uism/apply-action assoc :account/id {:none {}})
                             (uism/assoc-aliased :email "" :password "" :failed? false)
                             (uism/reset-actor-ident :actor/current-account (uism/with-actor-class [:account/id :none] Session))
                             (uism/trigger-remote-mutation :actor/current-account `logout {})
                             (uism/activate :state/gathering-credentials))))}})}}})

(defn ui-login-form [{:keys [email password failed?] :as login-form} checking?]
  (div :.ui.segment
    (h2 "Username is bob@example.com, password is letmein")
    (div :.ui.form {:classes [(when failed? "error")
                              (when checking? "loading")]}
      (div :.field
        (label "Email")
        (input {:value    (or email "")
                :onChange (fn [evt] (raw/set-value!! raw-app login-form :email (evt/target-value evt)))}))
      (div :.field
        (label "Password")
        (input {:type     "password"
                :onChange (fn [evt] (raw/set-value!! raw-app login-form :password (evt/target-value evt)))
                :value    (or password "")}))
      (div :.ui.error.message
        "Invalid credentials. Please try again.")
      (div :.field
        (button :.ui.primary.button {:onClick (fn [] (uism/trigger! raw-app :sessions :event/login {}))} "Login")))))

(defn RootUISMSessions [_]
  (let [{:actor/keys [login-form current-account]
         :keys       [active-state]
         :as         sm} (rc3/use-uism raw-app session-machine :sessions {})]
    (log/info "UISM content" (with-out-str (pprint sm)))
    ;; TODO: Not done yet...didn't have time to finish refining, but it looks like it'll work
    (case active-state
      :state/logged-in (div :.ui.segment
                         (dom/p {} (str "Hi," (:account/email current-account)))
                         (button :.ui.red.button {:onClick #(uism/trigger! raw-app :sessions :event/logout)} "Logout"))
      (:state/checking-session :state/gathering-credentials) (ui-login-form login-form (= :state/checking-session active-state))
      (div (str active-state)))))

(ws/defcard raw-uism-card
  {::wsm/align {:flex 1}}
  (ct.react/react-card
    (dom/create-element RootUISMSessions {})))