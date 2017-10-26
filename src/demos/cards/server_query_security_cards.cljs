(ns cards.server-query-security-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [recipes.server-query-security-client :as client]
    [fulcro.client.cards :refer [fulcro-app]]
    [fulcro.client.dom :as dom]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
    [fulcro.client.core :as fc]
    [fulcro.client.primitives :as om]))

(dc/defcard security-card
  "
  # Securing Server Queries

  Note: This is a full-stack example. Make sure you're running the server and are serving this page from it.
  "
  (fulcro-app client/Root)
  {}
  {:inspect-data false})

(dc/defcard-doc
  "
  ## UI Query Security

  If you examine any UI query it will have a tree form. That is the nature of Om query and Datomic pull syntax. It
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
      :parser (om/parser {:read logging-query :mutate logging-mutate})
      :parser-injections #{:authentication}
      :components {
                   ; Server security demo: This puts itself into the Ring pipeline to add user info to the request
                   :auth-hook      (server-security/make-authentication)
                   ; This is here as a component so it can be injected into the parser env for processing security
                   :authentication (server-security/make-authorizer)}))
  ```

  You can see the remainder of the sample implementation in `server-query-security-server.clj`.
")
