(ns fulcro-devguide.H10-Server-Interactions-Full-Stack-Mutations
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.mutations :as m]
            [fulcro.client.core :as fc]))

(defcard-doc "
  ## Mutations

  ### Optimistic (client) changes

  When you define a mutation you define a local `action`. With full-stack mutations, this is referred to as an optimistic
  update, with the assumption that the server will be sent the same instruction to complete whenever it can. This allows
  the UI to be very snappy and move forward without waiting for the server.

  ### Sending and responding to server writes

  The section on mutations already covered the basics of indicating that a mutation is full-stack.
  A mutation is sent to the server when it returns a map with a key `:remote` and either a value of `true` or an AST (abstract
  syntax tree). The helper macro `defmutation` indicates full-stack by including one or more remote sections:

  ```clojure
  (require [fulcro.client.mutations :refer [mutate]])

  (defmethod mutate 'some/mutation [env k params]
    ;; sends this mutation with the same `env`, `k`, and `params` arguments to the server
    {:remote true
     :action (fn[] ... )})
  ```

  ```
  (defmutation do-thing [params]
    (action [env] ...)
    (remote [env] true))
  ```

  ```
  (defmutation do-thing [params]
    (action [env] ...)
    (remote [{:keys [ast]}] (assoc ast :params {:x 1})) ; change the param list for the remote
  ```

  ```clojure
  (defmethod mutate 'some/mutation [{:keys [ast] :as env} k params]
    ;; changes the mutation dispatch key -- the assumption is that the server processes
    ;; 'some/mutation as part of a different server-side mutation
    {:remote (assoc ast :key 'server/mutation :dispatch-key 'server/mutation)
     :action (fn[] ...)}))
  ```

  **Note:** The action is executed **before** the remote, and is done in multiple passes (your mutation is invoked for each pass).
  So if you want to delete data from the client, but will need the data to compute the remote expression, then you will
  typically pass the needed data as a parameter to the mutation so that the mutation invocation closes over it and you
  don't have to rely on state.

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

  ### Writing Server Mutations

  Server-side mutations in Fulcro are written the same way as on the client: A mutation returns a map with a key `:action`
  and a function of no variables as a value. That function then creates, updates, and/or deletes data from the data
  storage passed in the `env` parameter to the mutation.

  If you're using `defmutation` on the client, then you may wish to use the `fulcro-parser` on the server (the default
  on the easy server) and then use the server-side version of `defmutation` from `fulcro.server`.

  #### New item creation – Temporary IDs

  Om has a built in function `tempid` that will generate an om-specific temporary ID. This allows the normalization
  and denormalization of the client side database to continue working while the server processes the new data and returns
  the permanent identifiers.

  WARNING: Because om mutations can be called multiple times (at least once and once per each remote),
   you should take care to not call `fulcro.client.primitives/tempid` inside your mutation.
   Instead call it from your UI code that builds the mutation params, thereby solving this problem.

  Here are the client-side and server-side implementations of the same mutation:

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

  The return value of a mutation may include the special key `:tempids`. This is a mapping from incoming temporary
  IDs to the real IDs they got assigned on the server.
  This will be passed back to the client, where Fulcro will *automatically* do a recursive walk of the client-side
  database to replace **every instance** of the temporary ID with the database id. Note that the map at the :tempids key can have
  more than one tempid to database-id pair.

  #### Tempid Remapping

  The server mutation is allowed to return a value. Normally the only value that makes sense is for temporary ID remapping as
  previoiusly discussed, because that is automatically processed:

  ```
  {:tempids {old-id new-id}}
  ```
  ")
