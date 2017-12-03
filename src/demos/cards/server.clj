(ns cards.server
  (:require [fulcro.server :as server]
            [taoensso.timbre :as timbre]
            cards.autocomplete
            cards.card_utils
            cards.cascading-dropdowns
            cards.declarative_mutation_refresh
            cards.legacy_loading_indicators
            cards.loading_data_basics
            cards.loading_in_response_to_UI_routing
            cards.paginating_large_lists_from_server
            cards.parallel_vs_sequential_loading
            cards.server_SQL_graph_queries
            cards.server_error_handling
            cards.server_query_security
            cards.server_return_values_as_data_driven_mutation_joins
            cards.server_return_values_manually_merging
            [fulcro.client.impl.parser :as op]
            [fulcro.easy-server :as core]
            [cards.server-query-security :as server-security]
            [fulcro.server :refer [server-read server-mutate]]
            [fulcro-sql.core :as sql]
            [com.stuartsierra.component :as component]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHARED SERVER FOR ALL EXAMPLES
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
      :parser (server/parser {:read logging-query :mutate logging-mutate})
      :parser-injections (cond-> #{:authorization}
                           include-postgres? (conj :sqldb))
      :components (cond-> {
                           ;; Server security demo components
                           ; Server security demo: This puts itself into the Ring pipeline to add user info to the request
                           :auth-hook     (server-security/make-authentication)
                           ; This is here as a component so it can be injected into the parser env for processing security
                           :authorization (server-security/make-authorizer)}
                    include-postgres? (merge {:sqldb (component/using
                                                       (sql/build-db-manager {})
                                                       [:config])})))))


