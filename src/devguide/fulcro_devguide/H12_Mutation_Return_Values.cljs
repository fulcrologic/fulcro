(ns fulcro-devguide.H12-Mutation-Return-Values
  (:require [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(defcard-doc "
  # Server Mutation Return Values

  The server mutation is always allowed to return a value. Normally the only value that makes sense is the temporary ID
  remapping as previoiusly discussed in the main section of full-stack mutations. It is automatically processed by the client
  and causes the tempid to be rewritten everywhere in your state and network backlog:

  ```
  ; server-side
  (defmutation new-thing [params]
    (action [env]
      ...
      {::prim/tempids {old-id new-id}}))
  ```

  In some cases you'd like to return other details. However, remember back at the beginning of this section we talked
  about how any data merge needs a tree of data and a query. With a mutation *there is no query*!
  As such, return values from mutations are **ignored by default** because there is no way to understand how to
  merge the result into your database. Remember we're trying to eliminate the vast majority of callback hell and keep
  asynchrony out of the UI. The processing pipeline is always: update the database state, re-render the UI.

  If you want to make use of the returned values from the server then you need to add code into the stage of remote
  processing that is normally used to merge incoming data (that has a query).

  There are two primary ways to accompish this (both are demonstrated with live examples in `src/demos` of Fulcro on github).

  ## Augmenting the Merge

  We'll talk about the sledge-hammer approach first: plug into Fulcro merge routines.

  Fulcro gives a hook for a `mutation-merge` function that you can install when you're creating the application. If you
  use a multi-method, then it will make it easier to co-locate your return value logic near the client-local mutation itself:

  ```
  (defmulti return-merge (fn [state mutation-sym return-value] mutation-sym))
  (defmethod return-merge :default [s m r] s)

  (new-fulcro-client :mutation-merge return-merge ...)
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

  This technique is fully general in terms of handling arbitrary return values, but is limited in that your only recourse
  is to merge the data into you app state. Of course, since your rendering is a pure function of app state this means you
  can, at the very least, visualize the result.

  This works, but is not the recommended approach because it is very easy to make mistakes that affect your entire
  application.

  NOTE: Mutation merge happens after server return values have been merged; however, it does happen *before* tempid remapping.
  Just work with the tempids, and they will be rewritten once your merge is complete.

  ## Using Mutation Joins

  There is a more congruent way to get your return value into the app state. When I say congruent, I mean that the mechanism
  leverages everything you already know about how Fulcro does data-driven applications. If your mutation *just had a query with
  it*, then we could just leverage the normal merge facilities to automatically put it in place!

  This is exactly what mutation joins do. The explicit syntax for a mutation join looks like this:

  ```
  `[{(f) [:x]}]
  ```

  but you never write them this way because the query doesn't have ident information and cannot aid normalization. Instead,
  you could write them like this:

  ```
  `[{(f) ~(om/get-query Item)}]
  ```

  Running a mutation with this notation allows you to return a value from the mutation that exactly matches the graph
  of the item, and it will be automatically normalized and merged. So, if the `Item` query ended up being
  `[:db/id :item/value]` then the server mutation could just return a simple map like so:

  ```
  ; server-side
  (defmutation f [params]
    (action [env]
       {:db/id 1 :item/value 42}))
  ```

  NOTE: At the time of this writing the query must come from a UI component that *has an ident*. Thus, mutations joins
  essentially normalize something into a specific entity in your database (determined by the ID of the return
  value and the ident on the query's component).

  ### Simpler Notation

  Since the remote side of mutations can return a boolean *or an AST*. Fulcro comes with two helper functions that can
  rewrite the AST of the mutation to modify the parameters or convert it to a mutation join! This can simplify how the
  mutations look in the UI.

  Here's the difference. With the manual syntactic technique we just described your UI and client mutation would look something like this:

  ```
  ; in the UI
  (transact! this `[{(f) ~(om/get-query Item)}])

  ; in your mutation definition (client-side)
  (defmutation f [params]
    (action [env] ...)
    (remote [env] true))
  ```

  However, using the helpers you can instead write it like this:

  ```
  (ns api
    (:require [fulcro.client.mutations :refer [returning]]))

  ; in the UI
  (transact! this `[(f)])

  ; in your mutation definition (client-side)
  (defmutation f [params]
    (action [env] ...)
    (remote [{:keys [ast state]}] (returning ast state Item))
  ```

  This makes the mutation join an artifact of the protocol, and less for you to manually code (and read) in the UI.

  The server-side code is the same for both: just return a proper graph value!

  ## What's Next?

  Now that we know how to get values back from mutations, let's talk about
  [triggering loads from within a mutation](#!/fulcro_devguide.H15_Triggering_Loads_from_Mutations).
  ")
