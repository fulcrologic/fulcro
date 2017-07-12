(ns cards.server
  (:require [om.next.server :as om]
            [taoensso.timbre :as timbre]
            [om.next.impl.parser :as op]
            [cards.server-api :as api]
            [recipes.background-loads-server :as bg]
            recipes.error-handling-server
            recipes.lazy-loading-visual-indicators-server
            recipes.load-samples-server
            recipes.mutation-return-value-server
            recipes.paginate-large-lists-server
            [recipes.server-query-security-server :as server-security]
            [recipes.websockets-server :as wsdemo]
            [fulcro.easy-server :as core]
            [fulcro.websockets.components.channel-server :as cs]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHARED SERVER FOR ALL EXAMPLE
;; We use namespacing on the query keys and mutations to isolate the various examples. All that needs to happen is
;; the loading of the server-side namespace of an example, which will add it's stuff to the multimethods defined
;; here
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn logging-mutate [env k params]
  (timbre/info "Mutation Request: " k)
  (api/server-mutate env k params))

(defn logging-query [{:keys [ast] :as env} k params]
  (timbre/info "Query: " (op/ast->expr ast))
  (api/server-read env k params))

(defn make-system []
  (core/make-fulcro-server
    :config-path "config/demos.edn"
    :parser (om/parser {:read logging-query :mutate logging-mutate})
    :parser-injections #{:authorization}
    ;; extra routes (for websockets demo)
    :extra-routes {:routes   ["" {["/chsk"] :web-socket}]
                   :handlers {:web-socket cs/route-handlers}}
    :components {
                 ;; Server security demo components
                 ; Server security demo: This puts itself into the Ring pipeline to add user info to the request
                 :auth-hook        (server-security/make-authentication)
                 ; This is here as a component so it can be injected into the parser env for processing security
                 :authorization    (server-security/make-authorizer)

                 ;; websocket components and route additions
                 :channel-server   (cs/make-channel-server)
                 :channel-listener (wsdemo/make-channel-listener)
                 }))

