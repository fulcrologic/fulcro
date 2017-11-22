(ns fulcro-devguide.H10-Server-Interactions-Mutations
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.mutations :as m]
            [fulcro.client.core :as fc]))

(defcard-doc "
  # Mutations

  You've already seen how to define local mutations on the client. That portion of a mutation is run immediately on the
  client, and when there is also a server interaction that client-side of the operation is an optimistic update (because
  you're assuming that the server will succeed in replicating the action).

  We'll talk more about handling errors in a bit, but first let's clear up how you go about telling the server it has
  something to do.

  ## Full-Stack Mutations

  The multi-method in Fulcro client (which can be manipualted with `defmutation`) can indicate that a given mutation
  should be sent to any number of remotes. The default remote is named `:remote`, but you can define new ones or even
  rename the default one.

  The technical structure of this looks like:

  ```clojure
  (require [fulcro.client.mutations :refer [mutate]])

  (defmethod mutate 'some/mutation [env k params]
    ;; sends this mutation with the same `env`, `k`, and `params` arguments to the server
    {:remote true
     :action (fn[] ... )})
  ```

  or

  ```
  (defmutation do-thing [params]
    (action [env] ...)
    (remote [env] true))
  ```

  or

  ```
  (defmutation do-thing [params]
    (action [env] ...)
    ; send (do-thing {:x 1}) even if params are different than that on the client
    (remote [{:keys [ast]}] (assoc ast :params {:x 1})) ; change the param list for the remote
  ```

  or

  ```clojure
  (defmethod mutate 'some/mutation [{:keys [ast] :as env} k params]
    ;; Changes the mutation from the incoming client-side (some/mutation) to (server/mutation)
    {:remote (assoc ast :key 'server/mutation :dispatch-key 'server/mutation)
     :action (fn[] ...)}))
  ```

  We generally recommend using the `defmutation` macro because it integrated nicely with IDEs and also prevents some kinds
  of mistakes (like making changes outside of the action).

  Basically, you use the name of the remote as an indicator of which remote you want to replicate the mutation to. From
  there you either return `true` (which means send the mutation as-is), or you may return an expression AST that represents
  the mutation you'd like to send instead. The `fulcro.client.primitives` namespace includes `ast->query` and `query-ast`
  for arbitrary generation of ASTs, but the original AST of the mutation is also available in the mutation's environment.

  Therefore, you can alter a mutation simply by altering and returning the `ast` given in `env`, as shown in the parameter
  and dispatch alterations above.

  **Note:** The action is executed **before** the remote, and the processing is done in multiple passes (your mutation
  handler is invoked for each pass).
  So if you want to delete data from the client, but will need the data to compute the remote expression, then you will
  typically pass the needed data as a *parameter* to the mutation so that the mutation invocation closes over it and you
  don't have to rely on state (which might have changed in an earlier pass).

  ## Writing The Server Mutations

  Server-side mutations in Fulcro are written the same way as on the client: A mutation returns a map with a key `:action`
  and a function of no variables as a value. The mutation then does whatever server-side operation is indicated. The `env`
  parameter on the server can contain anything you like (for example database access interfaces), and you'll see how
  to configure that when you study the `I_Building_A_Server` section.

  The recommended approach to writing a server is to use the pre-written server-side parser and multimethods, which allow you
  to mimic that code structure of the client (there is a `defmutation` in `fulcro.server` for this).

  If you're using this approach (which is the default in the easy server), then here are the client-side and server-side
  implementations of the same mutation:

  ```
  ;; client
  ;; src/my_app/mutations.cljs
  (ns my-app.mutations
    (:require [fulcro.client.mutations :refer [defmutation]]))

  (defmutation do-something [{:keys [p]}]
    (action [{:keys [state]}]
      (swap! state assoc :value p))
    (remote [env] true))
  ```

  ```
  ;; server
  ;; src/my_app/mutations.clj
  (ns my-app.mutations
    (:require [fulcro.server :refer [defmutation]]))

  (defmutation do-something [{:keys [p]}]
    (action [{:keys [some-server-database]}]
       ... server code to make change ...))
  ```

  It is recommended that you use the same namespace on the client and server for mutations so it is easy to find them,
  but the macro allows you to namespace the symbol if you choose to use a different namespace on the server:

  ```
  (ns my-app.server
    (:require [fulcro.server :refer [defmutation]]))

  (defmutation my-app.mutations/do-something [{:keys [p]}]
    (action [{:keys [some-server-database]}]
       ... server code to make change ...))
  ```

  The ideal structure for many people is to use a CLJC file, where they can co-locate the mutations in the same
  source file. The only trick is that you have to make sure you use the correct `defmutation`!

  ```
  (ns my-app.api
    (:require
      #?(:clj [fulcro.server :refer [defmutation]]
         :cljs [fulcro.client.mutations :refer [defmutation]])))

  #?(:clj (defmutation do-thing [params]
            (action [server-env] ...))
      :cljs (defmutation do-thing [params]
              (remote [env] true)))
  ```

  Please see I_Building_A_Server for more information about setting up a server with injected components in the mutation
  environment.

  #### New item creation – Temporary IDs

  Fulcro has a built in function `prim/tempid` that will generate a unique temporary ID. This allows the normalization
  and denormalization of the client side database to continue working while the server processes the new data and returns
  the permanent identifiers.

  The idea is that these temporary IDs can be safely placed in your client database (and network queues), and will be
  automatically rewritten to their real ID when the server has managed to create the real persistent entity. Of course, since
  you have optimistic updates on the client it is important that things go in the correct sequence, and that queued operations
  for the server don't get confused about what ID is correct!

  WARNING: Because om mutations can be called multiple times (at least once and once per each remote),
  you should take care to not call `fulcro.client.primitives/tempid` inside your mutation.
  Instead call it from your UI code that builds the mutation params.

  Fulcro's implementation works as follows:

  1. Mutations always run in the order specified in the call to `transact!`
  2. Transmission of separate calls to `transact!` run in the order they were called.
  3. If remote mutations are separated in time, then they go through a sequential networking queue, and are processed
  in order.
  4. As mutations complete on the server, they return tempid remappings. Those are applied to the application state *and*
  network queue before the next remote mutation is sent.

  This set of rules helps ensure that you can reason about your program, even in the presence of optimistic updates that
  could theoretically be somewhat ahead of the server.

  For example, you could create an item, edit it, then delete it. The UI responds immediately, but the initial create might
  still be running on the server. This means the server has not even given it a real ID before you're queuing up a request
  to delete it! With the above rules, it will just work! The network queue will have two backlogged operations (the edit
  and the delete), each with the same tempid that the items is known as in the database. When the create finally returns
  it will rewrite all of the tempids in state and the network queues. Thus, the edit will apply to the current server
  entity, as will the delete.

  All the server code has to do is return a map with the special key `:tempids` whose value is a map of `tempid->realid`
  whenever it sees an ID during persistence operations.
  Here are the client-side and server-side implementations of the same mutation that create a new item:

  ```
  ;; client
  ;; src/my_app/mutations.cljs
  (ns my-app.mutations
    (:require [fulcro.client.mutations :refer [defmutation]]))

  (defmutation new-item [{:keys [tempid text]}]
    (action [{:keys [state]}]
      (swap! state assoc-in [:item/by-id tempid] {:db/id tempid :item/text text}))
    (remote [env] true))
  ```

  ```
  ;; server
  ;; src/my_app/mutations.clj
  (ns my-app.mutations
    (:require [fulcro.server :refer [defmutation]]))

  (defmutation new-item [{:keys [tempid text]}]
    (action [{:keys [state]}]
      (let [database-tempid (make-database-tempid)
            database-id (add-item-to-database database {:db/id database-tempid :item/text text})]
        {:tempids {tempid database-id}})))
  ```

  Other mutation return values are covered in a later section of this guide.

  #### Mutation Fallbacks

  Fulcro has support for error handling via what are called transaction fallbacks:

  ```
  (prim/transact! this `[(some/mutation) (fulcro.client.data-fetch/fallback {:action handle-failure})])
  ```

  ```
  (require [fulcro.client.mutations :refer [mutate]])

  (defmethod mutate 'some/mutation [{:keys [state] :as env} k params]
    {:remote true
     :action (fn [] (swap! state do-stuff)})

  (defmethod mutate 'handle-failure [{:keys [state] :as env} k {:keys [error] :as params}]
    ;; fallback mutations are designed to recover the client-side app state from server failures
    ;; so, no need to send to the server
    {:action (fn [] (swap! state undo-stuff error)))
  ```

  Assuming that `some/mutation` returns `{:remote true}` (or `{:remote AST}`)  this sends `some/mutation` to the server.
  If the server throws an error then the fallback action's mutation symbol (a dispatch key for mutate) is invoked on the
  client with params that include an `:error` key that includes the details of the server exception (error type, message,
  and ex-info's data). Be sure to only include serializable data in the server exception!

  You can have any number of fallbacks in a tx, and they will run in order if the transaction fails.

  While the fallback mechanism does make it possible to capture errors, it is also true that errors in an optimisitc UI
  can be confusing to users (after all, they've already moved on from the operation in question to something else). This
  is a valid concern, but consider the kinds of errors that are possible:

  1. The network is down/flaky. This case is rare, and is easy for the user to understand. If you make sure your server
  interactions are idempotent, then this kind of error can be dealt with by making your network layer auto-retry on
  recoverable network errors.
  2. There is a bug. All bets are off. Your user is going to have a bad experience. If you knew what the bug was going to do,
  you'd have fixed it. This case is not worth worrying about because there is no reliable way to handle it.
  3. There is a serious problem on the server. If it shows up as a network error, then see (1). Otherwise, it's more like (2).
  4. Someone is trying to hack your system (security). Throw an exception on the server. Your UI isn't even involved.

  So, if you write your UI in a way that doesn't rely on the server to tell you the user is doing something wrong (e.g.
  you know what a user is allowed to do, so don't show them illegal operations), then for the most part the only reasonable
  error to \"handle\" is a temporary network outage.

  As a result it is not recommended that you rely on fallbacks for very much. They are provided for cases where you'd
  like to code instance-targeted recovery, but we believe this to be a rarely useful feature.

  You're much better off preventing errors by coding your UI to validate,
  authorize, and error check things on the client before sending them to the server. The server should still verify
  sanity for security reasons, but optimistic systems like Fulcro put more burden on the client code in order to
  provide a better experience under normal operation.

  In general it can be difficult to recover from real hard failures, and this is true for any application. The difference
  for optimistic systems is that the user can be several steps ahead in the UI of the operation that is failing.
  Fortunately, the overall user experience is better for the happy cases (which hopefully are 99.9999% of them), but
  it is true that if you're user is 10 steps ahead of the server and the server barfs, your easiest route to recovery
  is to throw up an error dialog and reload all questionable state.

  You probably also need to clear the network queue so that additional queued operations don't continue to fail.

  #### Clearing Network Queue

  If the server sends back a failure it may be desirable to clear any pending network requests from the client
  network queue. For example, if you're adding an item to a list and get a server error you might have a mutation waiting
  in your network queue that was some kind of modification to that (now failed) item. Continuing the network processing
  might just cause more errors.

  The FulcroApplication protocol (implemented by your client app) includes the protocol method
  `clear-pending-remote-requests!` which will drain all pending network requests.

  ```
  (fulcro.client.core/clear-pending-remote-requests! my-app)
  ```

  A common recovery strategy from errors could be to clean the network queue and run a mutation that resets your application
  to a known state, possibly loading sane state from the server.

  #### Remote reads after a mutation

  In earlier sections you learned that you can list properties after your mutation to indicate re-renders.
  These follow-on read keywords are always local re-render reads, and nothing more:

  ```
  (prim/transact! this '[(app/f) :thing])
  ; Does mutation, and re-renders anything that has :thing in a query
  ```

TODO: CONTINUE REWRITE HERE...

  Instead, we supply access to the internal mutation we use to queue loads, so that remote reads are simple and explicit:

  ```
  ; Do mutation, then run a remote read of the given query, along with a post-mutation
  ; to alter app state when the load is complete.
  (prim/transact! this `[(app/f) (fulcro/load {:query ~(prim/get-query Thing) :post-mutation after-load-sym}])
  ```

  Of course, you can (and *should*) use syntax quoting to embed a query from (prim/get-query) so that normalization works,
  and the post-mutation can be used to deal with the fact that other parts of the UI may want to, for example, point
  to this newly-loaded thing. The `after-load-sym` is a symbol (e.g. dispatch key to the mutate multimethod). The multi-method
  is guaranteed to be called with the app state in the environment, but no other Om environment items are ensured at
  the moment.

  IMPORTANT: post mutations look like regular mutations, but *only* the `action` is applied. Remotes are not processed
  on post mutations (or fallbacks) because they are not meant to trigger further network interactions, just app state
  adjustments.

  ")
