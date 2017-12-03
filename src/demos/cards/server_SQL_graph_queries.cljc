(ns cards.server-SQL-graph-queries
  (:require
    #?@(:clj  [[fulcro-sql.core :as sql]
               [clojure.java.jdbc :as jdbc]
               [taoensso.timbre :as timbre]]
        :cljs [[devcards.core :as dc :include-macros true]
               [fulcro.client.cards :refer [defcard-fulcro]]])
               [fulcro.client.dom :as dom]
               [fulcro.server :as server]
               [fulcro.client.data-fetch :as df]
               [fulcro.client.logging :as log]
               [fulcro.client :as fc]
               [fulcro.ui.bootstrap3 :as b]
               [fulcro.client.primitives :as prim :refer [defui defsc]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
#?(:clj
   (def schema
     {:fulcro-sql.core/graph->sql {:settings/auto-open?          :settings/auto_open
                                   :settings/keyboard-shortcuts? :settings/keyboard_shortcuts}
      :fulcro-sql.core/joins      {:account/members  (sql/to-many [:account/id :member/account_id])
                                   :account/settings (sql/to-one [:account/settings_id :settings/id])
                                   :member/account   (sql/to-one [:member/account_id :account/id])}
      :fulcro-sql.core/pks        {}}))

; This is the only thing we wrote for the server...just return some value so we can
; see it really talked to the server for this query.
#?(:clj
   (server/defquery-root :graph-demo/accounts
     (value [{:keys [sqldb query]} params]
       (let [db              (sql/get-dbspec sqldb :accounts)
             all-account-ids (jdbc/query db ["select id from account"] {:row-fn :id :result-set-fn set})]
         (timbre/info all-account-ids)
         (if (seq all-account-ids)
           (sql/run-query db schema :account/id query all-account-ids)
           [])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLIENT:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defui ^:once Settings
  static prim/IQuery
  (query [this] [:db/id :settings/auto-open? :settings/keyboard-shortcuts?])
  static prim/Ident
  (ident [this props] [:settings/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [db/id settings/auto-open? settings/keyboard-shortcuts?]} (prim/props this)]
      (dom/ul nil
        (dom/li nil (str "Auto open? " auto-open?))
        (dom/li nil (str "Enable Keyboard Shortcuts? " keyboard-shortcuts?))))))

(def ui-settings (prim/factory Settings {:keyfn :db/id}))

(defui ^:once Member
  static prim/IQuery
  (query [this] [:db/id :member/name])
  static prim/Ident
  (ident [this props] [:member/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [db/id member/name]} (prim/props this)]
      (dom/div nil
        "Member: " name))))

(def ui-member (prim/factory Member {:keyfn :db/id}))

(defui ^:once Account
  static prim/IQuery
  (query [this] [:db/id :account/name
                 {:account/members (prim/get-query Member)}
                 {:account/settings (prim/get-query Settings)}])
  static prim/Ident
  (ident [this props] [:account/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [db/id account/name account/members account/settings]} (prim/props this)]
      (dom/div nil
        (dom/h3 nil (str "Account for " name))
        (dom/ul nil
          (when (seq settings)
            (dom/li nil
              (dom/h3 nil "Settings")
              (ui-settings settings)))
          (dom/li nil
            (dom/h3 nil "Members in the account")
            (map ui-member members)))))))

(def ui-account (prim/factory Account {:keyfn :db/id}))

(defui ^:once Root
  static prim/IQuery
  (query [this] [:ui/react-key {:accounts (prim/get-query Account)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key db/id accounts]} (prim/props this)]
      (dom/div #js {:key react-key}
        (dom/h3 nil "Accounts with settings and users")
        (map ui-account accounts)))))

#?(:cljs
   (dc/defcard-doc
     "# Graph Queries Against SQL

     This demo leverages the `fulcro-sql` library to run UI graph queries against an SQL database. The
     [documentation for fulcro-sql](https://github.com/fulcrologic/fulcro-sql) covers the full setup.

     NOTE: To run this demo, you must:

     1. Run your server REPL with -Dpostgres as a JVM argument.
     2. Install PostgreSQL on your machine
     3. Create a database called `accounts` (e.g. createdb accounts)
     4. Run this page against the running demo server (i.e. port 8081)

     Files of interest:

     `src/demos/config/migrations/V1__account_schema.sql` - Initial schema and some sample rows
     `src/demos/config/demos.edn` - Configuration for demo server, include sql db config
     `src/demos/config/accountpool.props` - HikariCP connection pool props. This is how it connects to your SQL database. *You might need to edit this one*.
     this file - The client and server-side code.

     This demo fully honors the UI query that is sent. That is to say if you were to remove properties from a query, then the
     server would not include them in the response.

     The initial load is exactly as you'd expect (nothing surprising):

     ```
     (df/load app :graph-demo/accounts Account {:target [:accounts]})
     ```

     The UI source is as you'd expect:
     "
     (dc/mkdn-pprint-source Settings)
     (dc/mkdn-pprint-source Member)
     (dc/mkdn-pprint-source Account)
     (dc/mkdn-pprint-source Root)))

#?(:cljs
   (defcard-fulcro sql-graph-demo
     Root
     {}
     {:inspect-data true
      :fulcro       {:started-callback
                     (fn [app]
                       (df/load app :graph-demo/accounts Account {:target [:accounts]}))}}))
