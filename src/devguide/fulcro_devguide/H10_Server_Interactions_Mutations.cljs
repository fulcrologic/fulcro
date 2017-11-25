(ns fulcro-devguide.H10-Server-Interactions-Mutations
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.mutations :as m]
            [fulcro.client.core :as fc]))

(defcard-doc
  "
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

  ; or using the with-params helper
  (defmutation do-thing [params]
    (action [env] ...)
    ; send (do-thing {:x 1}) even if params are different than that on the client
    (remote [{:keys [ast]}] (m/with-params ast {:x 1})) ; change the param list for the remote
  ```

  or

  ```clojure
  (defmethod mutate 'some/mutation [{:keys [ast] :as env} k params]
    ;; Changes the mutation from the incoming client-side (some/mutation) to (server/mutation)
    {:remote (assoc ast :key 'server/mutation :dispatch-key 'server/mutation)
     :action (fn[] ...)}))
  ```

  You could even go as far as using the functions in the `parser` namespace to generate a completely new AST node that
  has nothing to do with the UI mutation.

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

  ### Writing The Server Mutations

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

  ## New item creation – Temporary IDs

  Fulcro has a built in function `prim/tempid` that will generate a unique temporary ID. This allows the normalization
  and denormalization of the client side database to continue working while the server processes the new data and returns
  the permanent identifiers.

  The idea is that these temporary IDs can be safely placed in your client database (and network queues), and will be
  automatically rewritten to their real ID when the server has managed to create the real persistent entity. Of course, since
  you have optimistic updates on the client it is important that things go in the correct sequence, and that queued operations
  for the server don't get confused about what ID is correct!

  WARNING: Because mutation code can be called multiple times (at least once + once per each remote),
  you should take care to not call `fulcro.client.primitives/tempid` inside your mutation.
  Instead call it from your UI code that builds the mutation params.

  Fulcro's implementation works as follows:

  1. Mutations always run in the order specified in the call to `transact!`
  2. Transmission of separate calls to `transact!` run in the order they were called.
  3. If remote mutations are separated in time, then they go through a sequential networking queue, and are processed
  in order.
  4. As mutations complete on the server, they return tempid remappings. Those are applied to the application state *and*
  network queue before the next network operation (load or mutation) is sent.

  This set of rules helps ensure that you can reason about your program, even in the presence of optimistic updates that
  could theoretically be somewhat ahead of the server.

  For example, you could create an item, edit it, then delete it. The UI responds immediately, but the initial create might
  still be running on the server. This means the server has not even given it a real ID before you're queuing up a request
  to delete it! With the above rules, it will just work! The network queue will have two backlogged operations (the edit
  and the delete), each with the same tempid that you currently know. When the create finally returns
  it will automatically rewrite all of the tempids in state and the network queues, then send the next operation. Thus,
  the edit will apply to the current server entity, as will the delete.

  All the server code has to do is return a map with the special key `:fulcro.client.primitives/tempids`
  (or the legacy `:tempids`) whose value is a map of `tempid->realid` whenever it sees an ID during persistence operations.
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
    (:require [fulcro.client.primitives :as prim]
              [fulcro.server :refer [defmutation]]))

  (defmutation new-item [{:keys [tempid text]}]
    (action [{:keys [state]}]
      (let [database-tempid (make-database-tempid)
            database-id (add-item-to-database database {:db/id database-tempid :item/text text})]
        {::prim/tempids {tempid database-id}})))
  ```

  Other mutation return values are covered in Mutation Return Values.

  ## Remote Reads After a Mutation

  In earlier sections you learned that you can list properties with your mutation to indicate re-renders.
  These follow-on read keywords are always local re-render reads, and nothing more:

  ```
  (prim/transact! this '[(app/f) :thing])
  ; Does mutation, and re-renders anything that has :thing in a query
  ```

  Fulcro will automatically queue remote reads *after* writes when they are submitted in the same thread interaction:

  ```
  (prim/transact! this `[(f)])
  (df/load this :thing Thing)
  (prim/transact! this `[(g)])
  ```

  will result in two network interactions. The first will run `[(f) (g)]`, and the second will be a load of `:thing`. This
  is a defined and official behavior.

  Thus, one way you can implement a sequence of mutation followed by learning a result is to run a mutation and a load.

  ## Using Loads as Mutations

  There is technically nothing wrong with issuing a load that has side-effects on the server. For example, one way to
  implement login is to issue a load with the user's credentials:

  ```
  (df/load :current-user User {:params {:username u :password p}})
  ```

  The server query response can validate the credentials, set a cookie, and return the user info all at once!

  If you remember from the General Operations section, you can modify the low-level Ring response by associating a lambda
  with your return value. If you were using Ring Session, then this might be how the query would be implemented on the server:

  ```
  (ns my-api
    (:require [fulcro.server :as server :refer [defmutation defquery-root]]))

  (def bad-user {:db/id 0})

  (defquery-root :current-user
    (value [env params]
      (if-let [{:keys [db/id] :as user} (authenticate params)]
        (server/augment-response user (fn [resp] (assoc-in resp [:session :uid] id)))
        bad-user)))
  ```

  ## Running Mutations in the Context of an Entity

  If you submit a transaction and include an ident:

  ```
  (transact! reconciler [:person/by-id 4] `[(f)])
  ```

  then the transaction will run as-if it were executed in the context of any live component on the screen that currently has
  that ident. This will make the ident available in the mutation's environment as `:ref`, and will focus refresh at that
  component sub-tree(s). This can be useful when you have out-of-band data that you're running using the reconciler.

  ## What's Next?

  Now that we have the basics of server interaction programming under our belt, let's
  [show the user that we're talking to the server.](#!/fulcro_devguide.H11_Server_Interactions_Network_Activity_Indicators)
  ")
