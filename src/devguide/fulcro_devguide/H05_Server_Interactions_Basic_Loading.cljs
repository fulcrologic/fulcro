(ns fulcro-devguide.H05-Server-Interactions-Basic-Loading
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.mutations :as m]
            [fulcro.client.core :as fc]))

(defcard-doc
  "
  # The Data Fetch API: `load`

  Let's say we want to load all of our friends from the server. A query has to be rooted somewhere, so we'll invent
  a root-level keyword, `:all-friends`, and join it to the UI query for a `Person`:

  ```
  [{:all-friends (prim/get-query Person)}]
  ```

  What we'd like to see then from the server is a return value with the shape of the query:

  ```
  { :all-frields [ {:db/id 1 ...} {:db/id 2 ...} ...] }
  ```

  If we combine that query with that tree result and were to manually call `merge!` we'd end up with this:

  ```
  { :all-frields [ [:person/by-id 1] [:person/by-id 2] ...]
    :person/by-id { 1 { :db/id 1 ...}
                    2 { :db/id 2 ...}
                    ...}}
  ```

  The data fetch API has a simple function for this that will do all of these together (query derivation, server
  interaction, and merge):

  ```
  (ns my-thing
    (:require [fulcro.client.data-fetch :as df]))

  ...

  (df/load comp-reconciler-or-app :all-friends Person)
  ```

  An important thing to notice is that `load` can be thought of as a function that loads normalized data into the
  root node of your graph (`:all-friends` appears in your root node).

  This is sometimes what you want, but more often you really want data loaded at some other spot in your graph. We'll
  talk about this more in a moment, first, let's see how to handle that on the server.

  ## Handling a Root Query

  If you're using the standard server support of Fulcro, then the API hooks are already predefined for you, and you
  can use helper macros to generate handlers for queries. If your `load` specified a keyword, then this is seen by
  the server as a load targeted to your root node. Thus, the server macro is called `defquery-root`:

  ```
  (ns api
    (:require [fulcro.server :refer [defquery-root defquery-entity defmutation]]))

  (defquery-root :all-friends
     \"optional docstring\"
     (value [env params]
        [{:db/id 1 :person/name ....} {:db/id 2 ...} ...]))
  ```

  It's as simple as that! Write a function that returns the correct value for the query. The query itself will be available
  in the `env`, and you can use libraries like `pathom`, `Datomic`, and `fulcro-sql` to parse those queries into the proper tree
  result from various data sources.

  ## Entity Queries

  You can also ask to load a specific entity. You do this simply by telling load the ident of the thing you'd like to
  load:

  ```
  (load this [:person/by-id 22] Person)
  ```

  Such a load will send a query to the server that is a join on that ident. The helper macro to handle those
  is `defquery-entity`, and is dispatched by the table name:

  ```
  (defquery-entity :person/by-id
     \"optional docstring\"
     (value [env id params]
        {:db/id id ...}))
  ```

  Note that this call gets an `id` in addition to parameters (in the above call, `id` would be 22). Again, the full query
  is in `env` so you can process the data driven response properly.

  ## Targeting Loads

  A short while ago we noted that loads are targeted at the root of your graph, and that this wasn't always what you
  wanted. After all, your graph database will always have other UI stuff. For example there might be a current screen, which
  might point to a friends screen, which in turn is where you want to load that list of friends:

  ```
  { :current-screen [:screens/by-type :friends]
    ...
    :screens/by-type { :friends { :friends/list [] ... }}}
  ```

  If you've followed our earlier recommendations, then your applications UI is normalized as well, and any given
  spot in your graph is really just an entry in a top-level table. Thus, the path to the desired location
  of our friends is usually just 3 deep. In this case: `[:screens/by-type :friends :friends/list]`.

  If we were to merge the earlier load into that database we could get what we want by just moving graph edges (where
  a to-one edge is an ident, and a to-many edge is a vector of idents):

  ```
  { :all-frields [ [:person/by-id 1] [:person/by-id 2] ...]  ; (1) MOVE this to (2)
    :person/by-id { 1 { :db/id 1 ...}
                    2 { :db/id 2 ...}
    :current-screen [:screens/by-type :friends]
    :screens/by-type { :friends { :friends/list [] ... }}} ; (2) where friends *should* be
  ```

  Since the graph database is just nodes and edges, there really aren't many more operations to worry about!
  You've essentially got to *normalize a tree*, *merge normalized data*, and *move graph edges*.

  The load API supports several kinds of graph edge targeting to allow you to put data where you want it in your
  graph. NOTE: Technically data is always loaded into the root, then relocated. So, be careful not to name your top-level
  edge after something that already exists there!

  ### Simple Targeting

  The simplest targeting is to just relocate an edge from the root to somewhere else. The `load` function can do
  that with a simple additional parameter:

  ```
  (df/load comp :all-friends Person {:target [:screens/by-type :friends :friends/list]})
  ```

  So, the server will still see the well-known query for `:all-friends`, but your local UI graph will end up seeing the
  results in the list on the friends screen.

  ### Advanced Targets

  You can also ask the target parameter to modify to-many edges instead of replacing them. For example, say you
  were loading one new person, and wanted to append it to the current list of friends:

  ```
  (df/load comp :best-friend Person {:target (df/append-to [:screens/by-type :friends :friends/list])})
  ```

  The `append-to` function in the `data-fetch` namespace augments the target to indicate that the incoming items
  (which will be normalized) should have their idents appended onto the to-many edge found at the given location.

  NOTE: `append-to` will not create duplicates.

  The other available helper is `prepend-to`. Using a plain target is equivalent to full replacement.

  You may also ask the targeting system to place the result(s) at more than one place in your graph. You do this
  with the `multiple-targets` wrapper:

  ```
  (df/load comp :best-friend Person {:target (df/multiple-targets
                                                (df/append-to [:screens/by-type :friends :friends/list])})
                                                [:other-spot]
                                                (df/prepend-to [:screens/by-type :summary :friends]))})
  ```

  Note that `multiple-targets` can use plain target vectors (replacement) or any of the special wrappers.

  ## Refreshing the UI After Load

  The component that issued the load will automatically be refreshed when the load completes. You may use the data-driven
  nature of the app to request other components refresh as well. The `:refresh` option tells the system what data has
  changed due to the load. It causes all live components that have queried those things to refresh.
  You can supply keywords and/or idents:

  ```
  ; load my best friend, and re-render every live component that queried for the name of a person
  (df/load comp :best-friend Person {:refresh [:person/name]})
  ```

  ## Other Load Options

  Loads allow a number of additional arguments. Many of these are discussed in more detail in later sections:

  - `:post-mutation` and `:post-mutation-params` - A mutation to run once the load is complete (local data transform only).
    Covered in Morphing Loaded Data.
  - `:remote` - The name of the remote you want to load from.
  - `:refresh` - A vector of keywords and idents. Any component that queries these will be re-rendered once the load completes.
  - `:marker` - Boolean or keyword. Indicates how (of if) load should indicate progress in your app state. Covered in
    Network Activity Indicators. Defaults to overwriting your target data with a load marker.
  - `:parallel` - Boolean. Defaults to false. When true, bypasses the sequential network queue. Allows multiple loads to run at once,
    but causes you to lose any guarantees about ordering since the server might complete them out-of-order.
  - `:fallback` - A mutation to run if the server throws an error during the load.
  - `:without` - A set of keywords to elide from the query. Covered in Incremental Loading.
  - `:params` - A map. If supplied the params will appear as the params of the query on the server.
  ")
