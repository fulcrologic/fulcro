(ns fulcro-devguide.H01-Server-Interactions-General-Operation
  (:require [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(defcard-doc
  "
  # Server interaction - General Operation

  One of the most interesting and powerful things about Fulcro is that the model for server interaction is unified into
  a clean data-driven structure. At first the new concepts can be challenging, but once you've seen the core primitive
  (component-based queries/idents for normalization) we think you'll find that it dramatically simplifies everything!

  In fact, now that you've completed the materials of this guide on the graph database, queries, idents, and
  normalization, it turns out that the server interactions become nearly trivial!

  Not only is the *structure* of server interaction well-defined, Fulcro come with two pre-written server-side code bases
  that handle all of the Fulcro plumbing for you. You can choose to provide as little or as much as you like. The easy
  server code provides everything in a Ring-based stack, and the modular style of server code provides something you can
  plug into just about anything (though it assumes it can behave a bit like Ring).

  Even then, there are a lot of possible pitfalls when writing distributed applications. People often underestimate just how hard
  it is to get web applications right because they forget that.

  So, while the *API and mechanics* of how you write Fulcro server interactions are as simple as possible there is no getting
  around that there are some hairy things to navigate in distributed apps independent of your choice of tools.
  Fulcro tries to make these things apparent, and it also tries hard to make sure you're able to get it right without
  pulling out your hair.

  Here are some of the basics:

  - Networking is provided.
     - The protocol is EDN on the wire (via transit), which means you just speak clojure data on the wire, and can
     easily extend it to encode/decode new types.
  - All network requests (queries and mutations) are processed sequentially unless you specify otherwise. This allows you
  to reason about optimistic updates (Starting more than one at a time via async calls could
  lead to out-of-order execution, and impossible-to-reason-about recovery from errors).
  - You may provide fallbacks that indicate error-handling mutations to run on failures.
  - Writes and reads that are enqueued together will always be performed in write-first order. This ensures that remote reads are
  as current as possible.
  - Any `:ui/` namespaced query elements are automatically elided when generating a query from the UI to a server, allowing you
  to easily mix UI concerns with server concerns in your component queries.
  - Normalization of a remote query result is automatic.
  - Deep merge of query results uses intelligent overwrite for properties that are already present in the client database
  (described in a section on Deep Merge).
  - Any number of remotes can be defined (allowing you to easily integrate with microservices)
  - Protocol and communication is strictly constrained to the networking layer and away from your application's core structure,
  meaning you can actually speak whatever and however you want to a remote. In fact
  the concept of a remote is just \"something you can talk to via queries and mutations\".
  You can easily define a \"remote\" that reads and writes browser
  local storage or a Datascript database in the browser. This is an extremely powerful generalization for isolating
  side-effect code from your UI.

  ## General Theory of Operation

  There are only a few general kinds of interactions with a server:

  - Initial loads when the application starts
  - Incremental loads of sub-graphs of something that was previously loaded.
  - Event-based loads (e.g. user or timed events)
  - Integrating data from other external sources (e.g. server push)

  In standard Fulcro networking, *all* of the above have the following similarities:

  - A component-based graph query needs to be involved (to enable auto-normalization). Even the server push (though in that case the client needs to know what **implied** question the server is sending data about.
  - The data from the server will be a tree that has the same shape as the query.
  - The data needs to be normalized into the client database.
  - Optionally: after integrating new data there may be some need to transform the result to a form the UI needs
  (e.g. perhaps you need to sort or paginate some list of items that came in).

  **IMPORTANT**: Remember what you learned about the graph database, queries, and idents. This section cannot possibly be understood
  properly if you do not understand those topics!

  ### Integration of *ALL* new external data is just a Query-Based Merge

  So, here is the secret: When external data needs to go into your database, it all uses the exact same mechanism: a
  query-based merge. So, for a simple load, you send a UI-based query to the server, the server responds with a tree
  of data that matches that graph query, and then the query itself (which is annotated with the components and ident functions)
  can be used to normalize the result. Finally, the normalized result can be merged into your existing client database.

  ```
  Query --> Server --> Response + Original Query w/Idents --> Normalized Data --> Database Merge --> New Database
  ```

  Any other kind of extern data integration just starts at the \"Response\" step by manually providing the query that
  is annotated with components/idents:

  ```
  New External Data + Query w/Idents --> Normalized Data --> Database Merge --> New Database
  ```

  There is a primitive function `merge!` function that implements this, so that you can simplify the picture to:

  ```
  Tree of Data + Query --> merge! --> New Database
  ```

  When you interact with a server through the data fetch API, remember that it is essentially doing a server interaction
  (send the query to get the response), but everything that happens after that is pretty much just `merge!`.

  ## The Central Functions : `transact!` and `merge!`

  When you start a Fulcro application your start-callback will get the completed `app` as a parameter.

  Inside of this app is a `reconciler`. The `reconciler` is a central component in the system, particularly with
  respect to remote interaction. It is the component that is responsible for managing the query engine, the remote
  networking, data merge, etc.

  There are some core central functions you should be well aware of, because they can be used from anywhere if used
  with the `reconciler`. It is therefore often also useful to make sure your `app` or `reconciler` are stored in
  a more global location where you can access them from outside of the application if you should need to.

  - `prim/transact!` : The central function for running abstract changes in the application. Can be run with a component
  or reconciler. If run with the reconciler, will typically cause a root re-render.
  - `prim/merge!` : A function that can be run against the reconciler to merge a tree of data via a UI query. This is
  the primary function that is used to integrate data in response to things like websocket server push.

  ## Augmenting the Ring Response

  In the following sections we'll be showing you how to respond to queries and mutations. The Ring stack is supplied for
  you in the server, but there are times when you need to modify something about the low-level response itself (such
  as adding a cookie).

  Any of the techniques you read about in the coming sections all allow you to wrap your response as follows:

  ```
  (fulcro.server/augment-response your-response (fn [ring-response] ring-response'))
  ```

  For example, if you were using Ring and Ring Session, you could cause a session cookie to be generated, and user
  information to be stored in a server session store simply by returning this from a query on user:

  ```
  (server/augment-response user (fn [resp] (assoc-in resp [:session :uid] real-uid)))
  ```

  We'll mention this again when you need to be reminded in an upcoming section.

  ## What's Next?

  Now, let's make sure you can [build a simple server](#!/fulcro_devguide.H02_Making_An_Easy_Server) to play with.
  ")

