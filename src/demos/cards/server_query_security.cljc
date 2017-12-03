(ns cards.server-query-security
  (:require
    #?@(:clj  [[fulcro.easy-server :as h]]
        :cljs [[devcards.core :as dc :include-macros true]
               [fulcro.client.cards :refer [defcard-fulcro]]])
               [fulcro.client.data-fetch :as df]
               [fulcro.client.mutations :as m]
               [fulcro.client.primitives :as prim :refer [defui defsc]]
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

#?(:clj
   (defrecord Authentication [handler]
     c/Lifecycle
     (start [this]
       (log/info "Hooking into pre-processing to add user info")
       (let [old-pre-hook (h/get-pre-hook handler)
             new-hook     (fn [ring-handler] (fn [req] ((old-pre-hook ring-handler) (assoc req :user {:username "Tony"}))))]
         (h/set-pre-hook! handler new-hook))
       this)
     (stop [this] this)))

#?(:clj
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

(defui ^:once Person
  static prim/IQuery
  (query [this] [:ui/fetch-state :name :address :cc-number])
  Object
  (render [this]
    (let [{:keys [name address cc-number]} (prim/props this)]
      (dom/div nil
        (dom/ul nil
          (dom/li nil (str "name: " name))
          (dom/li nil (str "address: " address))
          (dom/li nil (str "cc-number: " cc-number)))))))

(def ui-person (prim/factory Person))

(defui ^:once Root
  static prim/IQuery
  (query [this] [:ui/react-key {:person (prim/get-query Person)} :fulcro/server-error])
  Object
  (render [this]
    (let [{:keys [ui/react-key person server-error] :or {ui/react-key "ROOT"} :as props} (prim/props this)]
      (dom/div #js {:key react-key}
        (when server-error
          (dom/p nil (pr-str "SERVER ERROR: " server-error)))
        (dom/button #js {:onClick #(df/load this :person Person {:refresh [:person]})} "Query for person with credit card")
        (dom/button #js {:onClick #(df/load this :person Person {:refresh [:person] :without #{:cc-number}})} "Query for person WITHOUT credit card")
        (df/lazily-loaded ui-person person)))))

#?(:cljs
   (dc/defcard-doc
     "
     ## UI Query Security

     If you examine any UI query it will have a tree form. That is the nature of Fulcro's query and Datomic pull syntax. It
     is also the nature of UI's. For any such query, you can imagine it as a graph walk:

     Take this query:

     ```
     [:a {:join1 [:b {:join2 [:c :d]}]}]
     ```

     If you think about how this looks in the server: each join walks from one table (or entity) to another through
     some kind of (forward or reverse) reference.

     ```
        QUERY PART                            IMPLIED DATABASE graph
     [:a {:join1                           { :a 6 :join1 [:tableX id1] }
                                                           \\
                                                            \\
                                                             \\|
             [:b {:join2                       :tableX { id1 { :id id1 :join2 [:tableY id2]
                                                                                 /
                                                                                /
                                                                              |/
                   [:c :d]}]}]                :tableY { id2 { :id id2 :c 4 :d 5 }}
   ```

     One idea that works pretty well for us is based on this realization: There is a starting point of this walk (e.g. I
     want to load a person), and the top-level detail *must* be specified (or implied at least) by the incoming query
     (load person 5, load all persons in my account, etc.).

     A tradition logic check always needs to be run on
     this object to see if it is OK for the user to *start* reading the database there.

     The problem that remains is that there is a graph query that could conceivably walk to things in the database that
     should not be readable. So, to ensure security we need to verify that the user:

     1. is allowed to read the specific *data* at the node of the graph (e.g. :a, :c, and :d)
     2. is allowed to *walk* across a given *reference* at that node of the graph.

     However, since both of those cases are essentially the same in practice (can the user read the given property), one
     possible algorithm simplifies to:

     - Create a whitelist of keywords that are allowed to be read by the query in question. This can be a one-time
     declarative configuration, or something dynamic based on user rights.
     - Verify the user is allowed to read the \"top\" object. If not, disallow the query.
     - Recursively gather up all keywords from the query as a set
     - Find the set difference of the whitelist and the query keywords.
     - If the difference if not empty, refuse to run the query

     # The Server Hooks

     This is one of the few examples that has extra source in the server itself. The `server.clj` file builds the demo
     server, and this example's auth mechanisms are set up as components and a parser injection there. The relevant
     code is:

     ```
     (defn make-system []
       (core/make-fulcro-server
         :config-path \"config/demos.edn\"
         :parser (prim/parser {:read logging-query :mutate logging-mutate})
         :parser-injections #{:authentication}
         :components {
                      ; Server security demo: This puts itself into the Ring pipeline to add user info to the request
                      :auth-hook      (server-security/make-authentication)
                      ; This is here as a component so it can be injected into the parser env for processing security
                      :authentication (server-security/make-authorizer)}))
     ```

     You can see the remainder of the sample implementation in `server-query-security-server.clj`.
   "))

#?(:cljs
   (defcard-fulcro security-card
     "
     # Securing Server Queries

     Note: This is a full-stack example. Make sure you're running the server and are serving this page from it.
     "
     Root))
