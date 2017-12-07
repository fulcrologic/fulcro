(ns fulcro-devguide.H15-Triggering-Loads-from-Mutations
  (:require [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(defcard-doc
  "
  ### Loads From Within Mutations

  It is often the case that a load results from user interaction with the UI. But it is also the case that the
  load isn't everything you want to do, or that you'd like to hide the load logic or base it on current state that
  the triggering component does not know.

  In reality `load` and `load-field` call `prim/transact!` under the hood, targeting fulcro's built-in `fulcro/load`
  mutation, which is responsible for sending your request to the server.

  By contrast, there are similar functions: `load-action` and `load-field-action`
  that **do not** call `prim/transact!`, but can be used to initialize a load inside of one of your own
  client-side mutations.

  Let's look at an example of a standard load. Say you want to load a list of people from the server:

  ```
  (require [fulcro.client.data-fetch :as df])

  (defsc Person [this props]
    {:query [:id :name]}
    ... )
  (def ui-person (prim/factory Person))

  (defsc PeopleList [this {:keys [people]}]
    {:query [:db/id :list-title {:people (prim/get-query Person}]
     :ident [:people-list/by-id :db/id]}
     (dom/div nil
       (if (seq people)
         (dom/button #js {:onClick #(df/load-field this :people)} \"Load People\")
         (map ui-person people))))
  ```

  Since we are in the UI and not inside of a mutation's action thunk, we can use `load-field` to initialize the
  call to `prim/transact!`.

  The action-suffixed load functions are useful when performing an action in the user interface that must *both* modify
  the client-side database *and* load data from the server. **NOTE**: You must use the result of the
  `fulcro.client.data-fetch/remote-load` funtion as the value of the `:remote` key in the mutation return value.

  ```
  (require [fulcro.client.data-fetch :as df]
           [fulcro.client.mutations :refer [mutate]]
           [app.ui :as ui])

  (defmutation change-view [{:keys [new-view]}]
    (action [{:keys [state] :as env}]
                (let [new-view-comp (cond
                                       (= new-view :main)  ui/Main
                                       (= new-view :settings) ui/Settings]
                  (df/load-action env new-view new-view-comp)  ;; Add the load request to the queue
                  (swap! state update :app/current-view new-view))}))
    (remote [env] (df/remote-load env))) ;; Tell Fulcro you did something that requires remote
  ```

  This snippet defines a mutation that modifies the app state to display the view passed in via the mutation parameters
  and loads the data for that view. A few important points:

  1. If an action thunk calls one or more `action`-suffixed load functions (which do nothing but queue the load
     request) then it MUST also call `remote-load` on the remote side.
  2. The `remote-load` function *changes* the mutation's dispatch key to `fulcro/load` which in turn triggers
     the networking layer that one or more loads are ready. IMPORTANT: Remote loading cannot be mixed with a mutation
     that also needs to be sent remotely. I.e. one could not send `change-view` to the server in this example.
  3. If you find yourself wanting to put a call to any `load-*` in a React Lifecycle method try reworking
     the code to use your own mutations (which can check if a load is really needed) and the use the action-suffixed
     loads instead. Lifecycle methods are often misunderstood, leading to incorrect behaviors like triggering loads
     over and over again.

  ### Using the `fulcro/load` Mutation Directly (NOT recommended)

  Fulcro has a built-in mutation `fulcro/load` (also aliased as `fulcro.client.data-fetch/load`).

  The mutation can be used from application startup or anywhere you'd run a mutation (`transact!`). This covers almost
  all of the possible remote data integration needs!

  The helper functions described above simply trigger this built-in Fulcro mutation
  (the `*-action` variants do so by modifying the remote mutation AST via the `remote-load` helper function).

  You are allowed to use this mutation directly in a call to `transact!`, but you should never need to.

  The arguments to this mutation include:

  - `query`: The specific query you'd like to send to the server
  - `post-mutation`: A symbol indicating a mutation to run after the load completes
  - `post-mutation-params` - An optional map  that will be passed to the post-mutation when it is called. May only contain raw data, not code!
  - `without`: A set of keywords that should be (recursively) removed from `query`
  - `refresh`: A vector of keywords that should cause re-rendering after the load.
  - `parallel`: True or false. If true, bypasses the sequential network queue
  - `target`: A key path where the result should be move to when the load is complete.
  - `marker`: True or false. If true, places a loading marker in place of the first top-level key in the query,
    of in place of the given database object if `ident` and `field` are set (e.g. by `load-field`)

  For most direct use-cases you'll probably skip using the `load-field` specific parameters. You can read
  the source of `load-field` if you'd like to simulate it by hand.

  For example:

  ```
  (load reconciler :prop nil)
  ```

  is a simple helper that is ultimately identical to:

  ```
  (prim/transact! reconciler '[(fulcro/load {:query [:prop]}) :ui/loading-data])
  ```

  (the follow-on read is to ensure load markers update).

  ## What's Next?

  Now that we know how to make loads happen behind the scenes.  You might be interested in knowing how to accomplish
  [incremental loading](#!/fulcro_devguide.H20_Incremental_Loading).
  ")
