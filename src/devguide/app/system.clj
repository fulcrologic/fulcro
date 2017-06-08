(ns app.system
  (:require
    [app.api :as api]
    [om.next.server :as om]
    [solutions.advanced-server :as soln]
    [taoensso.timbre :as timbre]
    [com.stuartsierra.component :as component]
    [untangled.easy-server :as h]
    solutions.putting-together
    [ring.util.response :as ring]))

;; IMPORTANT: Remember to load all multi-method namespaces to ensure all of the methods are defined in your parser!

(defn logging-mutate [env k params]
  (timbre/info "Mutation Request: " k)
  ; If you want to see things happen, add in a delay:
  ; (Thread/sleep 2000)
  (api/apimutate env k params))

; build the server
(defn make-system []
  (h/make-untangled-server
    ; where you want to store your override config file
    :config-path "/usr/local/etc/app.edn"
    ; Standard Om parser
    :parser (om/parser {:read api/api-read :mutate logging-mutate})
    ; The keyword names of any components you want auto-injected into the parser env (e.g. databases)
    :parser-injections #{}
    ; Additional components you want added to the server
    :components {}))
