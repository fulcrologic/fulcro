(ns fulcro-devguide.H15-Server-Interactions-Triggering-Loads-from-Mutations
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.mutations :as m]
            [fulcro.client.core :as fc]))

(defcard-doc
  "
  ### Loads From Within Mutations

  `load` and `load-field` will call `prim/transact!` under the hood, targeting fulcro's built-in `fulcro/load`
  mutation, which is responsible for sending your request to the server. By contrast, `load-action` and `load-field-action`
  **do not** call `prim/transact!`, but can be used to initialize a load inside of one of your own
  client-side mutations.

  Let's look at an example of a standard load. Say you want to load a list of people from the server:

  ```
  (require [fulcro.client.data-fetch :as df])

  (defui Person
    static prim/IQuery (query [this] [:id :name :ui/fetch-state])
    ... )
  (def ui-person (prim/factory Person))

  (defui PeopleList
    static prim/IQuery (query [this] [:db/id :list-title {:people (prim/get-query Person}]
    static prim/Ident (ident [this props] [:people-list/by-id (:db/id props)])
    Object
    (render [this]
      (let [{:keys [people]} (prim/props this)]
        ;; people starts out as nil
        (dom/div nil
          (df/lazily-loaded #(map ui-person %) people
            :not-present-render #(dom/button #js {:onClick #(df/load-field this :people)}
                                   \"Load People\"))))))
  ```

  Since we are in the UI and not inside of a mutation's action thunk, we want to use `load-field` to initialize the
  call to `prim/transact!`. The use of `lazily-loaded` above will show a button to load people when `people` is `nil`
  (for example, when the app initially loads), and will render each person in the list of people once the button is
  clicked and the data has been loaded. By including `:ui/fetch-state` in the subcomponent's query, `lazily-loaded`
  is able to render different UIs for ready, loading, and failure states as well. See the
  [lazy loading cookbook recipe](https://github.com/fulcrologic/fulcro/blob/master/src/demos/cards/lazy_loading_indicators_cards.cljs)
  for a running example.

  The action-suffixed load functions are useful when performing an action in the user interface that must *both* modify
  the client-side database *and* load data from the server. **NOTE**: You must use the result of the
  `fulcro.client.data-fetch/remote-load` funtion as the value of the `:remote` key in the mutation return value.

  ```
  (require [fulcro.client.data-fetch :as df]
           [fulcro.client.mutations :refer [mutate]]
           [app.ui :as ui]) ;; namespace with defuis

  (defmethod mutate 'app/change-view [{:keys [state] :as env} _ {:keys [new-view]}]
    {:remote (df/remote-load env) ;; (2)
     :action (fn []
                (let [new-view-comp (cond
                                       (= new-view :main)  ui/Main
                                       (= new-view :settings) ui/Settings]
                (df/load-action env new-view new-view-comp) ;; (1)
                (swap! state update :app/current-view new-view))})
  ```

  If you'd rather use `defmutation`, it looks nearly identical:

  ```
  ; note, the mutation is now in the namespace of declaration (not app anymore)
  (defmutation change-view [{:keys [new-view]}]
    (action [{:keys [state] :as env}]
                (let [new-view-comp (cond
                                       (= new-view :main)  ui/Main
                                       (= new-view :settings) ui/Settings]
                  (df/load-action env new-view new-view-comp)
                  (swap! state update :app/current-view new-view))}))
    (remote [env] (df/remote-load env)))
  ```

  This snippet defines a mutation that modifies the app state to display the view passed in via the mutation parameters
  and loads the data for that view. A few important points:

  1. If an action thunk calls one or more `action`-suffixed load functions (which do nothing but queue the load
     request) then it MUST also call `remote-load` on the remote side.
  2. The `remote-load` function *changes* the mutation's dispatch key to `fulcro/load` which in turn triggers
     the networking layer that one or more loads are ready.
  3. If you find yourself wanting to put a call to any `load-*` in a React Lifecycle method try reworking
     the code to use your own mutations (which can check if a load is really needed) and the use the action-suffixed
     loads instead. Lifecycle methods are often misunderstood, leading to incorrect behaviors like triggering loads
     over and over again. To learn more about the dangers of loads and lifecycle methods, see the [reference
     on loading data]().

  ### Using the `fulcro/load` Mutation Directly

  Fulcro has a built-in mutation `fulcro/load` (also aliased as `fulcro.client.data-fetch/load`).

  The mutation can be used from application startup or anywhere you'd run a mutation (`transact!`). This covers almost
  all of the possible remote data integration needs!

  The helper functions described above simply trigger this built-in Fulcro mutation
  (the `*-action` variants do so by modifying the remote mutation AST via the `remote-load` helper function).

  You are allowed (and sometimes encouraged) to use this mutation directly in a call to `transact!`.
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
  [incremental loading](#!/fulcro_devguide.H20_Server_Interactions_Incremental_Loading).
  ")
