(ns recipes.sql-graph-query-server
  (:require [fulcro.server :as om]
            [fulcro.client.impl.parser :as op]
            [fulcro.server :refer [defquery-root defquery-entity defmutation server-mutate]]
            [taoensso.timbre :as timbre]
            [fulcro.easy-server :as core]
            ;[fulcro-sql.core :as sql]
            [clojure.java.jdbc :as jdbc]))

;(def schema
;  {::sql/graph->sql {:settings/auto-open?          :settings/auto_open
;                     :settings/keyboard-shortcuts? :settings/keyboard_shortcuts}
;   ::sql/joins      {:account/members  (sql/to-many [:account/id :member/account_id])
;                     :account/settings (sql/to-one [:account/settings_id :settings/id])
;                     :member/account   (sql/to-one [:member/account_id :account/id])}
;   ::sql/pks        {}})

; This is the only thing we wrote for the server...just return some value so we can
; see it really talked to the server for this query.
;(defquery-root :graph-demo/accounts
;  (value [{:keys [sqldb query]} params]
;    (let [db              (sql/get-dbspec sqldb :accounts)
;          all-account-ids (jdbc/query db ["select id from account"] {:row-fn :id :result-set-fn set})]
;      (timbre/info all-account-ids)
;      (if (seq all-account-ids)
;        (sql/run-query db schema :account/id query all-account-ids)
;        []))))
