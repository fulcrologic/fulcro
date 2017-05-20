(ns cards.load-samples-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [recipes.load-samples-client :as client]
    [untangled.client.cards :refer [untangled-app]]
    [om.dom :as dom]
    [untangled.client.data-fetch :as df]
    [untangled.client.logging :as log]
    [untangled.client.core :as uc]))

(dc/defcard load-samples-card
  "
  # Loading Samples Demo

  "
  (untangled-app client/Root
    :started-callback (fn [app]
                        ; Make sure you're running the app from the real server port (not fighweel).
                        ; This is a sample of loading a list of people into a given target, including
                        ; use of params. The generated network query will result in params
                        ; appearing in the server-side query, and :people will be the dispatch
                        ; key. The subquery will also be available (from Person)
                        (df/load app :load-samples/people client/Person {:target [:lists/by-type :enemies :people]
                                                                         :params {:kind :enemy}})
                        (df/load app :load-samples/people client/Person {:target [:lists/by-type :friends :people]
                                                                         :params {:kind :friend}})))
  {}
  {:inspect-data false})

(dc/defcard-doc
  "# Loading Data

  This demo shows two very common ways that data is loaded (or reloaded) in an application. This is a full-stack demo,
  and you must be running this card from the correct server URL or it will not work. See the general instructions for
  the demo.

  On startup, the following loads are run via the started callback:

  ```
  (uc/new-untangled-client
    :started-callback
      (fn [app]
        (df/load app :load-samples/people client/Person {:target [:lists/by-type :enemies :people]
                                                         :params {:kind :enemy}})
        (df/load app :load-samples/people client/Person {:target [:lists/by-type :friends :people]
                                                         :params {:kind :friend}})))
  ```

  The `load` function is the general-purpose workhorse for loading arbitrary data into the database. Most often it
  is given a keyword (which will be seen by the server as the top-level join key), a component (which defines the
  graph query for the data desired), and a load configuration.

  In this demo we're loading lists of people (thus the keyword's name). There are two kinds of people available from the
  server: friends and enemies. We use the `:params` config parameter to add a map of parameters to the network request
  to specify what we want. The `:target` key is the path to the (normalized) entity's property under which the response
  should be stored once received.

  Since all components should be normalized, this target path is almost always 2 (loading a whole component into a table)
  or 3 elements (loading one or more things into a property of an existing component).

  The server-side code for handling these queries uses a global table on the server, and is:

  ```
  (def all-users [{:db/id 1 :person/name \"A\" :kind :friend}
                  {:db/id 2 :person/name \"B\" :kind :friend}
                  {:db/id 3 :person/name \"C\" :kind :enemy}
                  {:db/id 4 :person/name \"D\" :kind :friend}])

  ; incoming param (kind) is destructured from third arg
  (defmethod api/server-read :load-samples/people [env _ {:keys [kind]}]
    (let [result (->> all-users
                   (filter (fn [p] (= kind (:kind p)))) ; get just the right kind of users
                   (mapv (fn [p] (-> p
                                   (select-keys [:db/id :person/name])
                                   (assoc :person/age-ms (System/currentTimeMillis))))))]
      {:value result}))
  ```

  Once started, any given person can be refreshed at any time. Timeouts, user event triggers, etc. There are two ways
  to refresh a given entity in a database. In the code of `Person` below you'll see that we are using `load` again, but
  this time with an `ident`:

  ```
  (df/load this (om/ident this props) Person)
  ```

  All of the parameters of this call can be easily derived when calling it from the component needing refreshed, so
  there is a helper function called `refresh!` that makes this a bit shorter to type:

  ```
  (df/refresh! this)
  ```

  The server-side implementation of refresh for person looks like this:

  ```
  ; ident join queries dispatch on the keyword in the ident
  (defmethod api/server-read :load-samples.person/by-id [{:keys [ast query-root] :as env} _ p]
    (let [id     (second (:key ast)) ; the key of the ast will be the ident, whose second member is the entity ID
          person (first (filter #(= id (:db/id %)) all-users))] ; use the all-users global in-memory db
      {:value (assoc person :person/age-ms (System/currentTimeMillis))}))
  ```

  The final kind of load (fill in a field of an already loaded element) is called `load-field`, and can be seen in action
  in the lazy loading indicators demo.
  "
  (dc/mkdn-pprint-source client/Person)
  (dc/mkdn-pprint-source client/People)
  (dc/mkdn-pprint-source client/Root))


