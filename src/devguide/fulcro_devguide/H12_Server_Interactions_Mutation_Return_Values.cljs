(ns fulcro-devguide.H12-Server-Interactions-Mutation-Return-Values
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.mutations :as m]
            [fulcro.client.core :as fc]))

(defcard-doc "
  #### Dealing with Server Mutation Return Values

  The server mutation is allowed to return a value. Normally the only value that makes sense is for temporary ID remapping as
  previoiusly discussed, because that is automatically processed:

  ```
  {:tempids {old-id new-id}}
  ```

  In some cases you'd like to return other details. However, remember back at the beginning of this section we talked
  about how any data merge needs a tree of data and a query. With a mutation *there is no query*!
  As such, return values from mutations are **ignored by default** because there is no way to understand how to
  merge the result into your database. Remember we're trying to eliminate the vast majority of callback hell. The processing
  pipeline is always: update the database state, re-render the UI.

  If you want to make use of the returned values from the server, then you need to add code into the stage of remote
  processing that is normally used to merge incoming data (that has a query). Fulcro gives a hook for a `mutation-merge`
  function that you can install when you're creating the application. If you use a multi-method, then it will make
  it easier to co-locate your return value logic near the client-local mutation itself:

  ```
  (defmulti return-merge (fn [state mutation-sym return-value] mutation-sym))
  (defmethod return-merge :default [s m r] s)

  (new-fulcro-client :mutation-merge return-merge)
  ```

  Now you should be able to write your return merging logic next to the mutation that it goes with. For example:

  ```
  (defmethod m/mutate 'some-mutation [env k p] {:remote true })
  (defmethod app/return-merge 'some-mutation [state k returnval] ...new-state...)
  ```

  Note that the API is a bit different between the two: mutations get the app state *atom* in an environment, and you
  `swap!` on that atom to change state. The return merge function is *in an already running* `swap!` during the state merge
  of the networking layer. So, it is a function that takes the application state as a *map* and must
  return a new state as a *map*.

  #### Getting a Remote Value after a Mutation

  There are two multiple techniques for getting values from a remote. The biggest thing to understand is that
  the mutation itself doesn't include enough information for the system to know how to merge a return value with
  the application database. Therefore, the return values of mutations, by default, are just discarded.

  One way to fix this uses primitives you already know: `load`.  Fulcro guarantees that mutations and loads that
  are called in the same sequence (sequential calls while you own the UI thread) are sequenced on the network so
  that mutations go first.

  This means that you can issue a load after a mutation to specifically read something from the server that you suspect
  has changed:

  ```
  (transact! this ...)
  (df/load this ...)  ; as long as you don't use `:parallel true`, this will be delayed until after the remote mutation
  ```

  There are two other ways to handle this kind of case, discussed elsewhere:

  - Declare the return type of a remote mutation so that the result can be merged.
  - Install a mutation-merge handler on the client to handle the return values of mutations.


  ")
