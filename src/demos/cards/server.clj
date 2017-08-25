(ns cards.server
  (:require [om.next.server :as om]
            [taoensso.timbre :as timbre]
            [om.next.impl.parser :as op]
            [recipes.background-loads-server :as bg]
            recipes.error-handling-server
            recipes.lazy-loading-visual-indicators-server
            recipes.load-samples-server
            recipes.mutation-return-value-server
            recipes.paginate-large-lists-server
            recipes.tabbed-interface-server
            recipes.autocomplete-server
            recipes.sql-graph-query-server
            [recipes.server-query-security-server :as server-security]
            [recipes.websockets-server :as wsdemo]
            [fulcro.easy-server :as core]
            [fulcro.server :refer [server-read server-mutate]]
            [fulcro-sql.core :as sql]
            [fulcro.websockets.components.channel-server :as cs]
            [com.stuartsierra.component :as component]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHARED SERVER FOR ALL EXAMPLE
;; We use namespacing on the query keys and mutations to isolate the various examples. All that needs to happen is
;; the loading of the server-side namespace of an example, which will add it's stuff to the multimethods defined
;; here
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; We're using Fulcro's multimethods, but logging access. This allows us to use the
; server-side query and mutation macros
(defn logging-mutate [env k params]
  (timbre/info "Mutation Request: " k)
  (server-mutate env k params))

(defn logging-query [{:keys [ast] :as env} k params]
  (timbre/info "Query: [" (op/ast->expr ast) "]")
  (server-read env k params))

(defn make-system []
  (let [include-postgres? (boolean (System/getProperty "postgres"))]
    (core/make-fulcro-server
      :config-path "config/demos.edn"
      ;; This is fulcro.server/fulcro-parser, but we've added in logging
      :parser (om/parser {:read logging-query :mutate logging-mutate})
      :parser-injections (cond-> #{:authorization}
                           include-postgres? (conj :sqldb))
      ;; extra routes (for websockets demo)
      :extra-routes {:routes   ["" {["/chsk"] :web-socket}]
                     :handlers {:web-socket cs/route-handlers}}
      :components (cond-> {
                           ;; Server security demo components
                           ; Server security demo: This puts itself into the Ring pipeline to add user info to the request
                           :auth-hook        (server-security/make-authentication)
                           ; This is here as a component so it can be injected into the parser env for processing security
                           :authorization    (server-security/make-authorizer)

                           ;; websocket components and route additions
                           :channel-server   (cs/make-channel-server)
                           :channel-listener (wsdemo/make-channel-listener)}
                    include-postgres? (merge {:sqldb (component/using
                                                       (sql/map->PostgreSQLDatabaseManager {})
                                                       [:config])})))))


