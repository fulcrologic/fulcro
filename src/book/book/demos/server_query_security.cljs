(ns book.demos.server-query-security
  (:require
    [fulcro.client.data-fetch :as df]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [com.rpl.specter :as s]
    [clojure.set :as set]
    [fulcro.client.dom :as dom]
    [fulcro.server :as server]
    [com.stuartsierra.component :as c]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
    [fulcro.client :as fc]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

(comment
  (defrecord Authentication [handler]
    c/Lifecycle
    (start [this]
      (log/info "Hooking into pre-processing to add user info")
      (let [old-pre-hook (h/get-pre-hook handler)
            new-hook     (fn [ring-handler] (fn [req] ((old-pre-hook ring-handler) (assoc req :user {:username "Tony"}))))]
        (h/set-pre-hook! handler new-hook))
      this)
    (stop [this] this))

  (defn make-authentication []
    (c/using (map->Authentication {}) [:handler])))

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

(def pretend-database {:person {:id 42 :name "Joe" :address "111 Nowhere" :cc-number "1234-4444-5555-2222"}})

(server/defquery-root :person
  (value [{:keys [ast authorization request query] :as env} params]
    (let [enforce-security? true
          ; The user is added by the authentication hook into Ring
          user              (:user request)]
      (log/info (str authorization "w/user" user))
      (when enforce-security?
        (or (and
              ;; of course, the params would be derived from the request/headers/etc.
              (can-access-entity? authorization user :person 42)
              (authorized-query? authorization user :person query))
          (throw (ex-info "Unauthorized query!" {:status 401 :body {:query query}}))))
      ;; Emulate a datomic pull kind of operation...
      (select-keys (get pretend-database :person) query))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLIENT:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def initial-state {:ui/react-key "abc"})

(defonce app (atom (fc/new-fulcro-client
                     :initial-state initial-state
                     :started-callback
                     (fn [{:keys [reconciler]}]
                       ; TODO
                       ))))

(defsc Person [this {:keys [name address cc-number]}]
  {:query [:ui/fetch-state :name :address :cc-number]}
  (dom/div nil
    (dom/ul nil
      (dom/li nil (str "name: " name))
      (dom/li nil (str "address: " address))
      (dom/li nil (str "cc-number: " cc-number)))))

(def ui-person (prim/factory Person))

(defsc Root [this {:keys [ui/react-key person fulcro/server-error] :or {ui/react-key "ROOT"} :as props}]
  {:query [:ui/react-key {:person (prim/get-query Person)} :fulcro/server-error]}
  (dom/div #js {:key react-key}
    (when server-error
      (dom/p nil (pr-str "SERVER ERROR: " server-error)))
    (dom/button #js {:onClick #(df/load this :person Person {:refresh [:person]})} "Query for person with credit card")
    (dom/button #js {:onClick #(df/load this :person Person {:refresh [:person] :without #{:cc-number}})} "Query for person WITHOUT credit card")
    (df/lazily-loaded ui-person person)))


