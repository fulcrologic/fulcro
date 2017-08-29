(ns cards.sql-graph-query-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [om.dom :as dom]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
    [fulcro.client.core :as fc]
    [fulcro.ui.bootstrap3 :as b]
    [om.next :as om :refer [defui]]))

(defui ^:once Settings
  static om/IQuery
  (query [this] [:db/id :settings/auto-open? :settings/keyboard-shortcuts?])
  static om/Ident
  (ident [this props] [:settings/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [db/id settings/auto-open? settings/keyboard-shortcuts?]} (om/props this)]
      (dom/ul nil
        (dom/li nil (str "Auto open? " auto-open?))
        (dom/li nil (str "Enable Keyboard Shortcuts? " keyboard-shortcuts?))))))

(def ui-settings (om/factory Settings {:keyfn :db/id}))

(defui ^:once Member
  static om/IQuery
  (query [this] [:db/id :member/name])
  static om/Ident
  (ident [this props] [:member/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [db/id member/name]} (om/props this)]
      (dom/div nil
        "Member: " name))))

(def ui-member (om/factory Member {:keyfn :db/id}))

(defui ^:once Account
  static om/IQuery
  (query [this] [:db/id :account/name
                 {:account/members (om/get-query Member)}
                 {:account/settings (om/get-query Settings)}])
  static om/Ident
  (ident [this props] [:account/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [db/id account/name account/members account/settings]} (om/props this)]
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

(def ui-account (om/factory Account {:keyfn :db/id}))

(defui ^:once Root
  static om/IQuery
  (query [this] [:ui/react-key {:accounts (om/get-query Account)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key db/id accounts]} (om/props this)]
      (dom/div #js {:key react-key}
        (dom/h3 nil "Accounts with settings and users")
        (map ui-account accounts)))))

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
  `src/demos/recipes/sql_graph_query_server.clj` - The server-side code.

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
  (dc/mkdn-pprint-source Root))

(defcard-fulcro sql-graph-demo
  Root
  {}
  {:inspect-data true
   :fulcro       {:started-callback
                  (fn [app]
                    (df/load app :graph-demo/accounts Account {:target [:accounts]}))}})
