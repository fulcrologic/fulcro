(ns cards.server
  (:require [om.next.server :as om]
            [taoensso.timbre :as timbre]
            [om.next.impl.parser :as op]
            [cards.server-api :as api]
            [recipes.background-loads-server :as bg]
            recipes.error-handling-server
            recipes.lazy-loading-visual-indicators-server
            [untangled.easy-server :as core]))

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
  (core/make-untangled-server
    :config-path "config/demos.edn"
    :parser (om/parser {:read logging-query :mutate logging-mutate})
    :parser-injections #{}
    :components {}))

