(ns recipes.server-query-security-server
  (:require [om.next.server :as om]
            [om.next.impl.parser :as op]
            [com.stuartsierra.component :as c]
            [untangled.easy-server :as h]
            [taoensso.timbre :as timbre]
            [com.rpl.specter :as s]
            [clojure.set :as set]))

; A map from "entry-level" concept/entity to a set of the allowed graph read/navigation keywords
(def whitelist {:person #{:name :address :mate}})

(defn keywords-in-query
  "Returns all of the keywords in the given (arbitrarily nested) query."
  [query] (set (s/select (s/walker keyword?) query)))

; TODO: determine if the user is allowed to start at the given keyword for entity with given ID
(defn authorized-root-entity?
  "Returns true if the given user is allowed to run a query rooted at the entity indicated by the combination of
  query keyword and entity ID.

  TODO: Implement some logic here."
  [user keyword id] (= "Tony" (:username user)))

(defn is-authorized-query?
  "Returns true if the given query is ok with respect to the top-level key of the API query (which should have already
  been authorized by `authorized-root-entity?`."
  [query top-key]
  (let [keywords-allowed  (get whitelist top-key #{})
        insecure-keywords (set/difference (keywords-in-query query) keywords-allowed)]
    (empty? insecure-keywords)))

(defrecord Authentication [handler]
  c/Lifecycle
  (start [this]
    (timbre/info "Hooking into pre-processing to add user info")
    (let [old-pre-hook (h/get-pre-hook handler)
          new-hook     (fn [ring-handler] (fn [req] ((old-pre-hook ring-handler) (assoc req :user {:username "Tony"}))))]
      (h/set-pre-hook! handler new-hook))
    this)
  (stop [this] this))

(defn make-authentication []
  (c/using (map->Authentication {}) [:handler]))

(defprotocol Auth
  (can-access-entity? [this user key entityid] "Check if the given user is allowed to access the entity designated by the given key and entity id")
  (authorized-query? [this user top-key query] "Check if the given user is allowed to access all of the data in the query that starts at the given join key"))

(defrecord Authorizer []
  c/Lifecycle
  (start [this] this)
  (stop [this] this)
  Auth
  (can-access-entity? [this user key entityid] (authorized-root-entity? user key entityid))
  (authorized-query? [this user top-key query] (is-authorized-query? query top-key)))

(defn make-authorizer [] (map->Authorizer {}))
(defmulti apimutate om/dispatch)
(defmulti api-read om/dispatch)

(def pretend-database {:person {:id 42 :name "Joe" :address "111 Nowhere" :cc-number "1234-4444-5555-2222"}})

(defmethod api-read :person [{:keys [ast authentication request query] :as env} dispatch-key params]
  (let [enforce-security? true
        ; The user is added by the authentication hook into Ring
        user              (:user request)]
    (when enforce-security?
      (or (and
            ;; of course, the params would be derived from the request/headers/etc.
            (can-access-entity? authentication user :person 42)
            (authorized-query? authentication user :person query))
        (throw (ex-info "Unauthorized query!" {:status 401 :body {:query query}}))))
    ;; Emulate a datomic pull kind of operation...
    {:value (select-keys (get pretend-database :person) query)}))

(defmethod apimutate :default [e k p]
  (timbre/error "Unrecognized mutation " k))

(defmethod api-read :default [{:keys [ast query] :as env} dispatch-key params]
  (timbre/error "Unrecognized query " dispatch-key (op/ast->expr ast)))



(defn logging-mutate [env k params]
  (timbre/info "Mutation Request: " k)
  (apimutate env k params))

(defn logging-query [{:keys [ast] :as env} k params]
  (timbre/info "Query: " (op/ast->expr ast))
  (api-read env k params))

(defn make-system []
  (h/make-untangled-server
    :config-path "config/recipe.edn"
    :parser (om/parser {:read logging-query :mutate logging-mutate})
    ; Inject the authentication bit
    :parser-injections #{:authentication}
    :components {
                 ; The auth hook puts itself into the Ring pipeline
                 :auth-hook      (make-authentication)
                 ; The authentication bit is for checking reads
                 :authentication (make-authorizer)}))
