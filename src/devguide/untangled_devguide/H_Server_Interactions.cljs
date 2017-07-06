(ns untangled-devguide.H-Server-Interactions
  (:require-macros [cljs.test :refer [is]]
                   [untangled-devguide.tutmacros :refer [untangled-app]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [untangled.client.mutations :as m]
            [untangled.client.core :as uc]))

; TODO: Explain Om's HATEOS (keyword listing in :keys)
; see: https://github.com/omcljs/om/wiki/Quick-Start-%28om.next%29
; TODO: Error handling (UI and server), exceptions, fallbacks, status codes
; Remember that global-error-handler is a function of the network impl
; TODO: (advanced) cookies/headers (needs extension to U.Server see issue #13)

(defui ^:once CategoryQuery
  static om/IQuery
  (query [this] [:db/id :category/name])
  static om/Ident
  (ident [this props] [:categories/by-id (:db/id props)]))

(defui ^:once ItemQuery
  static om/IQuery
  (query [this] [:db/id {:item/category (om/get-query CategoryQuery)} :item/name])
  static om/Ident
  (ident [this props] [:items/by-id (:db/id props)]))

(def sample-server-response
  {:all-items [{:db/id 5 :item/name "item-42" :item/category {:db/id 1 :category/name "A"}}
               {:db/id 6 :item/name "item-92" :item/category {:db/id 1 :category/name "A"}}
               {:db/id 7 :item/name "item-32" :item/category {:db/id 1 :category/name "A"}}
               {:db/id 8 :item/name "item-52" :item/category {:db/id 2 :category/name "B"}}]})

(def component-query [{:all-items (om/get-query ItemQuery)}])

(def hand-written-query [{:all-items [:db/id :item/name
                                      {:item/category [:db/id :category/name]}]}])

(defui ^:once ToolbarItem
  static om/IQuery
  (query [this] [:db/id :item/name])
  static om/Ident
  (ident [this props] [:items/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [item/name]} (om/props this)]
      (dom/li nil name))))

(def ui-toolbar-item (om/factory ToolbarItem {:keyfn :db/id}))

(defui ^:once ToolbarCategory
  static om/IQuery
  (query [this] [:db/id :category/name {:category/items (om/get-query ToolbarItem)}])
  static om/Ident
  (ident [this props] [:categories/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [category/name category/items]} (om/props this)]
      (dom/li nil
        name
        (dom/ul nil
          (map ui-toolbar-item items))))))

(def ui-toolbar-category (om/factory ToolbarCategory {:keyfn :db/id}))

(defui ^:once Toolbar
  static om/IQuery
  (query [this] [{:toolbar/categories (om/get-query ToolbarCategory)}])
  Object
  (render [this]
    (let [{:keys [toolbar/categories]} (om/props this)]
      (dom/div nil
        (dom/button #js {:onClick #(om/transact! this '[(server-interaction/group-items)])} "Trigger Post Mutation")
        (dom/button #js {:onClick #(om/transact! this '[(server-interaction/group-items-reset)])} "Reset")
        (dom/ul nil
          (map ui-toolbar-category categories))))))

(defmethod m/mutate 'server-interaction/group-items-reset [{:keys [state]} k p]
  {:action (fn [] (reset! state (om/tree->db component-query sample-server-response true)))})

(defn add-to-category
  "Returns a new db with the given item added into that item's category."
  [db item]
  (let [category-ident (:item/category item)
        item-location (conj category-ident :category/items)]
    (update-in db item-location (fnil conj []) (om/ident ItemQuery item))))

(defn group-items
  "Returns a new db with all of the items sorted by name and grouped into their categories."
  [db]
  (let [sorted-items (->> db :items/by-id vals (sort-by :item/name))
        category-ids (-> db (:categories/by-id) keys)
        clear-items (fn [db id] (assoc-in db [:categories/by-id id :category/items] []))
        db (reduce clear-items db category-ids)
        db (reduce add-to-category db sorted-items)
        all-categories (->> db :categories/by-id vals (mapv #(om/ident CategoryQuery %)))]
    (assoc db :toolbar/categories all-categories)))


(defmethod m/mutate 'server-interaction/group-items [{:keys [state]} k p]
  {:action (fn [] (swap! state group-items))})

(defcard-doc
  "
  # Server interaction

  NOTE: This section's information is true; however, it was originally written from the perspective of someone that
  had tried Om Next before coming to Untangled. the Getting Started Guide may be easier to understand as a first
  exposure to server interactions, since it more assumes you'd just like to make something work.
  This document could use some improvements and simplifications, so we recommend
  that you look at the [Getting Started Guide](https://github.com/awkay/untangled/blob/develop/GettingStarted.adoc) first.

  The semantics of server request processing in Untangled have a number of guarantees
  that Om does not (out of the box) provide:

  - Networking is provided
  - All network requests (queries and mutations) are processed sequentially. This allows you
  to reason about optimistic updates (Starting more than one at a time via async calls could
  lead to out-of-order execution, and impossible-to-reason-about recovery from errors).
  - You may provide fallbacks that indicate error-handling mutations to run on failures

  ## General Theory of Operation

  Untangled uses the primitives of Om Next, but one of the primary contributions it makes to that model is the
  realization that the following use-cases are by far the most common:

  - Reading data on initial app startup
  - Triggering loads on user-based events (or perhaps timeouts)

  In both cases, the implementation of the remoting in Om Next is very similar:

  - Write something into your application state database the indicates you want to do some remote operation
      - This triggers an internal parsing that you would normally have to interact with in order to decide
        what to ask the server.
      - Note that 'writing something to your app state' is a *mutation*.
  - Morph the UI query into something your server would rather see (you don't want to write server queries for
    every darn UI tree you can think of on the client side)
  - Morph the response *back into UI shape*, and normalize it. Note this is a data transform aided by Om primitives.

  So, the key realization is the *in practice Om reads requires some kind of **mutation** before a read can occur*! The
  additional realization is the after the read run, and you've normalized data into the database, you often *want* to
  morph the database into something special on your UI that the server need not understand.

  So, Untangled provides the 'triggering' mutations, and gives you a way to hook into the result so that you can
  do this post-transform when the remote read is complete.

  ## The `untangled/load` Mutation

  Untangled has a built-in mutation called `untangled/load`. This mutation *can* be used directly from `om/transact!` (and often is).

  The `load` mutation does the following basic things:

  - Places your query onto a built-in queue in your app state of things to load
  - The mutation is marked `remote`, so it will cause the networking side of Om to trigger.
  - The networking plumbing of Untangled looks in the queue when it sees a `load` mutation. It elides the `load` from the remote request itself, and
    instead sends the desired query/queries, then properly merges the results.
  - It may (optionally) place load markers in your app state so you can show in-progress indicators
  - It may (optionally) run post-mutation transforms on the database (if you supply them)

  The mutation can be used from application startup or anywhere you'd run a mutation (`transact!`). This covers almost
  all of the possible remote data integration needs!

  First, let's cover some helper functions that make using this mechanism a bit easier.

  ## Load helpers

  Untangled includes a few helper functions that can assist you in invoking the `untangled/load` mutation.

  1. `load` and `load-action` : Newer, preferred API for most loads
  1. `load-data` and `load-data-action` : Alternate API. Harder to use for some cases.
  2. `load-field` and `load-field-action` : Field-targeted API (for lazy loading a component field)

  The former of each pair are methods that directly invoke a `transact!` to place load requests into a load queue.

  The latter of each pair are low-level implementation method that can be used to *compose* remote reads with your
  own mutations (e.g. you want to switch to a new area of the UI (local mutation), but also trigger a *remote read* to load the
  content of that UI at the same time).

  ### Load vs. Load Data vs. Load Field

  All of these functions are calls to the built-in `untangled/load` mutation behind the scenes, so requests made by these functions
  go through the same networking layer and have similar named parameters (see the doc strings
  in the [data-fetch namespace](https://github.com/untangled-web/untangled-client/blob/master/src/untangled/client/data_fetch.cljs)).
  The only difference is in how the query is specified. `load` requires a top-level keyword and a component, `load-data`
  must be passed a complete query, and `load-field` uses the passed-in component and a field name to create its query.
  `load` and `load-data` are subtle variants with the former being more consice, and the latter more generally flexible.

  `load-field` is really just a helper for a common use-case: loading a field of some specific thing on the screen. It
  focuses the component's query to the specified field, associates the component's ident with the query,
  and asks the UI to re-render all components with the calling component's ident after the load is complete. Since `load-field`
  requires a component to build the query sent to the server it cannot be used with the reconciler. 
  
  #### Use case - Initial load

  In Untangled, initial load is an explicit step. You simply put calls to `load` or `load-data` in your app start callback.
  State markers are put in place that allow you to then render the fact that you are loading data. Any number of separate
  server queries can be queued, and the queries themselves are used for normalization. Post-processing of the response
  is well-defined and trivial to access.

  ```
  (uc/new-untangled-client
    :initial-state {}
    :started-callback
      (fn [{:keys [reconciler] :as app}]
        (df/load app :items CollectionComponent {:without #{:comments} :post-mutation 'app/build-views})
        ; OR (less preferred)
        (df/load-data reconciler [{:items (om/get-query CollectionComponent)}]
                                   :without #{:comments}
                                   :post-mutation 'app/build-views)))
  ```

  In the above example the client is created (which must be mounted as a separate step). Once the application is mounted 
  it will call the `:started-callback` which in turn will trigger a load. These helper functions are really a call to
  om `transact!` that places `ready-to-load` markers in the app state, which in turn triggers the network plumbing. The
  network plumbing pulls these from the app state and processes them via the server and all of the normalization bits of 
  Om (and Untangled).

  The `:without` parameter will elide portions of the query. So for example, if you'd like to lazy load some portion of the
  collection (e.g. comments on each item) at a later time you can prevent the server from being asked to send them.

  The `:post-mutation` parameter is the name of the mutation you'd like to run on a successful result of the query. If there
  is a failure, then a failure marker will be placed in the app state, which you can have programmed your UI to react to
  (e.g. showing a dialog that has user-driven recovery choices).

  #### Use case - Loading a specific entity from the server

  The `load` function can accept an ident instead of a top-level property:

  ```
  (df/load app [:person/by-id 22] Person)
  ```

  which will load the specific entity (assuming your server responsds with it) into you client database. This could be
  used, for example, to refresh a particular entity in your client database.

  Giving a post mutation as a parameter would allow you to further integrate that entity into you views. Post mutations are 
  run after the query, and allow you to modify the application state as you see fit.

  NOTE: the `untangled.core/integrate-ident!` function is particularly handy for peppering the ident of the resulting item
  around the views via a post mutation.

  #### Use case - Loading a collection into an entity

  In this case you'd like to send the server some well-defined query, like `[{:all-people (om/get-query Person)}]`, but
  that will place the collection at the root of your app database under the `:all-people` key. The `:target` parameter
  can be used to redirect the result.

  Assume your database has the following state representing some UI Pane in a tab:

  ```
  { :friends { :tab { :people [] }}}
  ```

  The following load would send the desired query and rewrite the result into the desired location (while also
  normalizing the incoming People):

  ```
  (df/load app :all-people Person {:target [:friends :tab :people]})
  ```

  resulting in:

  ```
  { :friends { :tab { :people [[:people/by-id 1] [:people/by-id 2]] }}
    :people/by-id { 1 { ... }
                    2 { ... }}
  ```

  #### Use case - Including parameters on the server query

  Sometimes you'll want to modify the generated client query to include parameters. For example, perhaps you'd like to
  limit the query from the prior example:

  ```
  (df/load app :all-people Person {:params {:limit 10} :target [:friends :tab :people]})
  ```

  would result in `{:limit 10}` being visible in the parameters when parsing the server query:

  ```
  ;; assuming your server-side read is a multimethod:
  (defmethod api-read :all-people [env k params]
     (let [limit (or (:limit params) 10)] ...))
  ```

  #### Use case - Lazy loading a field

  One common operation is to load data in response to a user interaction. Interestingly, the query that you might
  have used in the initial load use case might have included UI queries for data you didn't want to fetch yet. So, we want
  to note that the initial load use-case supports eliding part of the query. For example, you can for example load an item without
  comments. Later, when the user wants to see comments you can supply a button that can load the comments on demand. The earlier
  use case on startup demonstrated this technique.

  The `load-field` function, which derives the query to send to the server from the component itself, can be used to trigger the 
  load of the subsequent data:

  ```
  (load-field this :comments)
  ```

  The only requirements are that the component has an Ident and the query for the component includes a join or property
  named `:comments` in the query.

  For example, say you had:

  ```
  (defui Comment 
     static om/IQuery
     (query [this] [:author :text]))

  (defui Item
     static om/IQuery
     (query [this] [:id :value {:comments (om/get-query Comment)}])
     static om/Ident
     (ident [this props] [:item/by-id (:id props)])
     Object
     (render [this]
        ...
        (dom/button #js { :onClick #(df/load-field this :comments) } \"Load comments\")
        ...)
  ```

  then clicking the button will result in the following query to the server:

  ```
  [{[:item/by-id 32] [{:comments [:author :text]]}]
  ```

  and the code to write for the server is now trivial. The dispatch key is :item/by-id, the 32 is accessible on the AST,
  and the query is a pull fragment that indicates the exact data to pull from your server database.

  Furthermore, the underlying code can easily put a marker in place of that data in the app state so you can show the
  'load in progress' marker of your choice.

  Untangled has supplied all of the Om plumbing for you.

  ### Load vs. Load-Action

  `load`, `load-field`, and `load-data` will call `om/transact!` under the hood, targeting untangled's built-in `untangled/load`
  mutation, which is responsible for sending your request to the server. By contrast, `load-action`, `load-field-action`,
  and `load-data-action` **do not** call `om/transact!`, but can be used to initialize a load inside of one of your own
  client-side mutations.

  Let's look at an example of a standard load. Say you want to load a list of people from the server:

  ```
  (require [untangled.client.data-fetch :as df])

  (defui Person
    static om/IQuery (query [this] [:id :name :ui/fetch-state])
    ... )
  (def ui-person (om/factory Person))

  (defui PeopleList
    static om/IQuery (query [this] [:db/id :list-title {:people (om/get-query Person}]
    static om/Ident (ident [this props] [:people-list/by-id (:db/id props)])
    Object
    (render [this]
      (let [{:keys [people]} (om/props this)]
        ;; people starts out as nil
        (dom/div nil
          (df/lazily-loaded #(map ui-person %) people
            :not-present-render #(dom/button #js {:onClick #(df/load-field this :people)}
                                   \"Load People\"))))))
  ```

  Since we are in the UI and not inside of a mutation's action thunk, we want to use `load-field` to initialize the
  call to `om/transact!`. The use of `lazily-loaded` above will show a button to load people when `people` is `nil`
  (for example, when the app initially loads), and will render each person in the list of people once the button is
  clicked and the data has been loaded. By including `:ui/fetch-state` in the subcomponent's query, `lazily-loaded`
  is able to render different UIs for ready, loading, and failure states as well. See the
  [lazy loading cookbook recipe](https://github.com/untangled-web/untangled-cookbook/tree/master/recipes/lazy-loading-visual-indicators)
  for a running example.

  The action-suffixed load functions are useful when performing an action in the user interface that must *both* modify
  the client-side database *and* load data from the server. **NOTE**: You must use the result of the
  `untangled.client.data-fetch/remote-load` funtion as the value of the `:remote` key in the mutation return value.

  ```
  (require [untangled.client.data-fetch :as df]
           [untangled.client.mutations :refer [mutate]]
           [app.ui :as ui]) ;; namespace with defuis

  (defmethod mutate 'app/change-view [{:keys [state] :as env} _ {:keys [new-view]}]
    {:remote (df/remote-load env) ;; (2)
     :action (fn []
                (let [new-view-query (cond
                                       (= new-view :main) (om/get-query ui/Main)
                                       (= new-view :settings) (om/get-query ui/Settings)]
                (df/load-data-action state new-view-query) ;; (1)
                (swap! state update :app/current-view new-view))})
  ```

  This snippet defines a mutation that modifies the app state to display the view passed in via the mutation parameters
  and loads the data for that view. A few important points:

  1. If an action thunk calls one or more `action`-suffixed load functions (which do nothing but queue the load
     request) then it MUST also use a call to `remote-load` for the remote keyword.
  2. The `remote-load` function *changes* the mutation's dispatch key to `untangled/load` which in turn triggers
     the networking layer that one or more loads are ready.
  3. If you find yourself wanting to put a call to any `load-*` in a React Lifecycle method try reworking
     the code to use your own mutations (which can check if a load is really needed) and the use the action-suffixed
     loads instead. Lifecycle methods are often misunderstood, leading to incorrect behaviors like triggering loads
     over and over again. To learn more about the dangers of loads and lifecycle methods, see the [reference
     on loading data]().

  ### Using the `untangled/load` Mutation Directly

  The helper functions described above simply trigger a built-in Untangled mutation called `untangled/load`
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
  (load-data reconciler [:prop])
  ```

  is a simple helper that is ultimately identical to:

  ```
  (om/transact! reconciler '[(untangled/load {:query [:prop]}) :ui/loading-data])
  ```

  (the follow-on read is to ensure load markers update).

  #### Getting a Remote Value after a Mutation

  The most common direct use of `untangled/load` is doing remote follow-on reads after a mutation (which itself may
  be remote).

  Note the difference. The following follow-on read is *always* just a local re-render in Untangled (in stock Om,
  the `:something` keyword can also trigger a remote read, though you'd have to write logic into the parser
  to figure it out):

  ```
  (om/transact! this '[(app/do-some-thing) :something])
  ```

  If what you want instead is `do-some-thing` and the read `:something` from the server, you instead want:

  ```
  (om/transact! this '[(app/do-some-thing) (untangled/load {:query [:something] :refresh [:something})])
  ```

  This has both a clear benefit and cost with respect to stock Om:

  - The benefits are:
      - Non-opaque reasoning
      - No need to write parser logic to figure out if you want to do remote reads
  - The costs are:
      - The UI is aware of what is remote, and what is local (in stock Om, the UI *can be* completely unconcerned
        about remoting, though in practice you want to trigger this kind of thing from the UI an expose it as
        some kind of mutation.)

  In practice, we've found that the idea that something involves a server is pretty clear-cut even at the UI layer,
  so the ability to completely abstract it away really isn't that necessary pragmatically, and the cost of writing
  the behind-the-scenes logic related making a parser trigger remote reads is somewhat high.

  #### A bit More About How it Works

  The `untangled/load` mutation does a very simple thing: It puts a state marker in a well-known location in your app state
  to indicate that you're wanting to load something (and returns `:remote true`). This causes the network
  plumbing to be triggered. The network plumbing only receives mutations that are marked remote, so it does the following:

  - It looks for the special mutations `untangled/load` and `untangled.client.data-fetch/fallback` (deprecated name is `tx/fallback`). The latter is part of the unhappy path handling.
     - For each load, it places a state marker in the app state at the target destination for the query data
     - All loads that are present are combined together into a single Om query
  - It looks for other mutations
  - It puts the 'other mutations' on the send queue (iff they are marked `:remote`)
  - It puts the derived query/queries from the `untangled/load`s onto the send queue.

  A separate \"thread\" (core async go block) watches the send queue, and sends things one-at-a-time (e.g. each entry
  in the queue is processed in a sequence, ensuring you can reason about things sequentially). The one-at-a-time
  semantics are very important for handling tempid reassignment, rational optimistic updates, and unhappy path handling.

  The send processing block (uses core async to make a thread-like infinite loop):

  - Pulls an item from the queue (or \"blocks\" when empty)
  - Sends it over the network
  - Updates the marker in the app state to `loading` (which causes a re-render, so you can show loading indicators)
  - \"waits\" for the response
      - On success: merges the data
      - On error: updates the state marker to an error state (which re-renders allowing the UI to show error UI)
  - Repeats in an infinite loop

  ### UI attributes

  Untangled recognizes the need to separate attributes that are UI-only and those that should actually be sent to
  the server. If a component, for example, wants to query for:

  ```
  [:ui/popup-visible :db/id :item/name]
  ```

  where the `popup-visible` item is actually in app state (a useful thing to do with most state instead of making
  it component local), then you have a problem when that component is composed into another component that
  is to be used when generating a server query. You don't want the UI-specific attributes to *go* to the server!

  Untangled handles this for you. Any attributes in your component queries that are namespaced to `ui` are automatically
  (and recursively) stripped from queries before they are sent to the server. There is nothing for you to do except
  namespace these local-only attributes in the queries! (Additionally, there are local mutation
  helpers that can be used to update these without writing custom mutation code. See the section on Mutation)

  ### Data merge

  Untangled will automatically merge (and normalize) the result of loads into the application client database.
  Untangled overrides the built-in Om shallow merge to a merge that has a number of extension that are useful for
  simple application reasoning:

  #### Deep Merge

  Untangled merges the response via a deep merge, meaning that existing data is not wiped out by default. Unfortunately,
  this causes a different problem. Let's say you have two UI components (with the same Ident) that ask for similar information:

  Component A asks for [:a]
  Component A2 asks for [:a :b]

  Of course, these queries are composed into a larger query, but you can imagine that if we use the query of A2, normalization
  will put something like this somewhere in the app state: `{ :a 1 :b 2}`. Now, at a later time, say we re-run a load but
  use component A's query. The response from the server will say something like `{:a 5}`, because all we asked for was
  `:a`!  But what if both A and A2 are on the screen??? Well, depending on how you merge strange things can happen.

  So, Untangled forms an opinion on this scenario:

  - First, since it isn't a great thing to do, you should avoid it
  - However, if you do it, Untangled merges with the following rules:
      - If the query *asks* for an attribute, and the *response does not include it*, then it is always removed from the app state since the
      server has clearly indicated it is gone.
      - If the query *does not ask* for an attribute (which means the response cannot possibly contain it), then Untangled
      will avoid removing it, even if other attributes come back (e.g. it will be a merge leaving the property that was
      not asked for alone). This does indicate that your UI is possibly in a state inconsistent with the server, which
      is the reason for the \"avoid this case\" advice.

  #### Normalization

  Normalization is always *on* in Untangled because your application will not work correctly if you don't normalize the
  data in your database. Loads must use real composed queries from the UI for normalization to work (the om `get-query` 
  function adds info to assit with normalization).

  Therefore, you almost *never* want to use a hand-written query that has not been placed on a `defui`. **It is perfectly
  acceptable to define queries via `defui` to ensure normalization will work**, and this will commonly be the case if your
  UI needs to ask for data in a structure different from what you want to run against the server.

  For example, say we have some items in our database, and we're planning on showing them grouped by category. We *could*
  write a server-side handler that could return them this way, but the grouping in the UI is really more of a UI
  concern (we might also want them sorted by name, etc, etc.). It makes a lot more sense to define a single server-side
  query that can return the items we want, and then define a UI-side transformation to run as a post-mutation that
  forms them into the desired structure for the UI.

  So, we need the data in our client database (normalized), but our UI might only have queries related to the grouped
  view. Thus, we don't have a UI query that meets our server query needs.

  In this situation we could write the following query-and-ident-only components:
  "
  (dc/mkdn-pprint-source ItemQuery)
  (dc/mkdn-pprint-source CategoryQuery)
  "

  The proper way to now ask the server would be to do something like join this query into a top-level key:

  "
  (dc/mkdn-pprint-source component-query)
  "

  The resulting query, if you were to ask for it with `om/get-query` would look the same as this hand-written
  query:

  "
  (dc/mkdn-pprint-source hand-written-query)
  "
  However, if you were to use the hand-written query then Om would not know how to normalize the server result
  into your database because the Ident functions would not be known (the `get-query` function adds metadata to
  the query to tell Om how to normalize the result`).

  The following card demonstrates the difference of merging the following server response: "
  (dc/mkdn-pprint-source sample-server-response))

(defn merge-using-query [state-atom query data]
  (let [db (om/tree->db query data true)]
    (swap! state-atom assoc :resulting-database db)))

(defcard query-response-demo
  "This card shows the difference between using hand-written queries and queries pulled from
  defui components (which need not have UI)."
  (fn [state-atom _]
    (dom/div nil
      (dom/button #js {:onClick #(merge-using-query state-atom hand-written-query sample-server-response)} "Merge with Hand-Written Query")
      (dom/button #js {:onClick #(merge-using-query state-atom component-query sample-server-response)} "Merge with Component Query")))
  {}
  {:inspect-data true})

(defcard-doc
  "
  and now perhaps you can see the true power of Untangled's approach to data loading! One server query implementation
  can now serve any of your needs for displaying these items.

  #### Post Mutations

  We've seen the power of using a component to generate queries that are simple for the server (even though the UI might
  want to display the result in a more complex way). Now, we'll finish the example by showing you how post mutations
  can complete the story (and for Om users, complete your understanding of why a custom parser isn't necessary in Untangled).

  In the prior section we talked about obtaining items that have categories. We wanted a simple server query so that
  obtaining the items was the same no matter what kind of UI was going to use them (a display of items grouped by category,
  for example).

  ```text
  Simple Query Result from Server
              |
     auto merge/normalize
              |
             \\|/
      Items with Categories
              |
         post mutation
              |
             \\|/
      Items by Category
  ```

  We all understand doing these kinds of transforms, so this technique gives some real concrete advantages overall:

  - Simple query to the server (only have to write one query handler)
      - Independent of the server's database structure (do items belong to categories, or just have them? etc).
  - Simple layout in resulting UI database (normalized into tables and a graph)
  - Straightforward data transform into what we want to show

  So, let's assume that the UI really wants to show a Toolbar that contains the items
  grouped by category, but our sample load is as before.

  The Untangled post-mutation can defined as:

  ```clojure
  (defmethod m/mutate 'server-interaction/group-items [{:keys [state]} k p]
    {:action (fn [] (swap! state group-items))})
  ```

  Where the UI components and helper functions are:

  "
  (dc/mkdn-pprint-source ToolbarItem)
  (dc/mkdn-pprint-source ToolbarCategory)
  (dc/mkdn-pprint-source Toolbar)
  (dc/mkdn-pprint-source add-to-category)
  (dc/mkdn-pprint-source group-items))

(defcard toolbar-with-items-by-category
  "This card allows you to simulate the post-mutation defined above, and see the resulting UI and database change. The
  Reset button will restore the db to the pre-mutation state, so you can A/B compare the before and after picture."
  (untangled-app Toolbar)
  (om/tree->db component-query sample-server-response true)
  {:inspect-data true})

(defcard-doc "
  ### Parameterized Reads

  You may add parameters to your remote reads using an optional argument to data fetch:

  ```
  (defui Article
    static om/IQuery (query [this] [:id :content {:comments (om/get-query Comments)}])
    static om/Ident (ident [this props] [:article/by-id (:id props)])
    (render [this]
      ;; render article content
      ;; ...
      ;; render a load comments button:
      (dom/button #js {:onClick #(df/load-field this :comments
                                  :params {:comments {:lowValue 0 :highValue 10}})}
        \"Load Comments\")))
  ```
  This sample query parameterizes the read to this article's comments with a range, say so that the server only returns
   the first ten most recent comments. The keys of the params map specify the keywords in the query that should be
   parameterized, and the values specify the parameter maps for their respective keys.

  So, `(load-field this :comments)` above would yield a query of the form:

  ```
  [{[:article/by-id 32] [{:comments [:other :props]]}]
  ```

  while `(load-field this :comments :params {:comments {:lowValue 0 :highValue 10}})` would yield a query of the form:

  ```
  [{[:article/by-id 32] [({:comments [:other :props]} {:lowValue 0 :highValue 10})]}]
  ```

  So, when you specify parameters to one of the items in your query, Untangled will add the parameters at that level
  of the query, which you will be able to access when parsing the query on the server-side read:

  ```
  (defmethod api-read :comments [{:keys [db ast]} k {:keys [lowValue highValue]}]
    {:value (get-comments-in-range db (:key ast) lowValue highValue)})
    ;; calls (get-comments-in-range db [:article/by-id 32] 0 10), assuming code snippet above

  (defmethod api-read :article/by-id [{:keys [parser query] :as env} k params]
    {:value (parser env query)})
  ```

  ### Query narrowing

  The load functions allow you to elide parts of the query using a `:without` set. This is useful when you have a query
  that would load way more than you need right now. Using the `:without` parameter on a `load` function will cause it
  to elide the portions of the query (properties/joins) that use the given keywords. See the loading sections below.

  ## Mutations

  ### Optimistic (client) changes

  There is no difference between optimistic updates in Untangled and in standard Om Next. You define a mutation, that
   mutation does a `swap!` on the `state` atom from the mutation's `env` parameter, and you're done. The details are
   covered in [Section G - Mutations](http://localhost:3449/#!/untangled_devguide.G_Mutation).

  ### Sending and responding to server writes

  Sending mutations to the server also behaves the same way that it does in standard Om Next. A mutation is sent to
  the server when it returns a map with a key `:remote` and either a value of `true` or of a modified query AST (abstract
  syntax tree). Here are examples of both:

  ```clojure
  (require [untangled.client.mutations :refer [mutate]])

  (defmethod mutate 'some/mutation [env k params]
    ;; sends this mutation with the same `env`, `k`, and `params` arguments to the server
    {:remote true
     :action (fn[] ... )})
  ```

  ```clojure
  (defmethod mutate 'some/mutation [{:keys [ast] :as env} k params]
    ;; adds the key-value pair {:extra :data} to the `params` that are sent to the server
    {:remote (assoc-in ast [:params :extra] :data)
     :action (fn[] ...)})
  )
  ```

  ```clojure
  (defmethod mutate 'some/mutation [{:keys [ast] :as env} k params]
    ;; changes the mutation dispatch key -- the assumption is that the server processes
    ;; 'some/mutation as part of a different server-side mutation
    {:remote (assoc ast :key 'server/mutation :dispatch-key 'server/mutation)
     :action (fn[] ...)})
  )
  ```

  **Note:** Om action thunks are executed **before** the remote read, so if you want to delete data from the client that you
  need to pass to the server, you will have to keep track of that state before removing it from your app state.

  #### Mutation Fallbacks

  One of the advantages to using Untangled is its support for error handling via what are called transaction fallbacks:

  ```
  (om/transact! this `[(some/mutation) (untangled.client.data-fetch/fallback {:action handle-failure})])
  ```

  ```
  (require [untangled.client.mutations :refer [mutate]])

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

  #### Clearing Network Queue

  If the server sends back a failure it may be desirable to clear any pending network requests from the client
  network queue. For example, if you're adding an item to a list and get a server error you might have a mutation waiting
  in your network queue that was some kind of modification to that (now failed) item. Continuing the network processing
  might just cause more errors.

  The UntangledApplication protocol (implemented by your client app) includes the protocol method
  `clear-pending-remote-requests!` which will drain all pending network requests.

  ```
  (untangled.client.core/clear-pending-remote-requests! my-app)
  ```

  A common recovery strategy from errors could be to clean the network queue and run a mutation that resets your application
  to a known state, possibly loading sane state from the server.

  #### Remote reads after a mutation

  In Om, you can list properties after your mutation to indicate re-renders. You can force them to be remote reads by
  quoting them. All of this requires complex logic in your parser to compare flags on the AST, process the resulting
  query in send (e.g. via process-roots), etc. It is more flexible, but the very common case can be made a lot more direct.

  ```
  (om/transact! this '[(app/f) ':thing]) ; run mutation and re-read thing on the server...
  ; BUT YOU implement the logic to make sure it is understood that way!
  ```

  In Untangled, follow-on keywords are always local re-render reads, and nothing more:

  ```
  (om/transact! this '[(app/f) :thing])
  ; Om and Untangled: Do mutation, and re-render anything that has :thing in a query
  ```

  Instead, we supply access to the internal mutation we use to queue loads, so that remote reads are simple and explicit:

  ```
  ; Do mutation, then run a remote read of the given query, along with a post-mutation
  ; to alter app state when the load is complete.
  (om/transact! this `[(app/f) (untangled/load {:query ~(om/get-query Thing) :post-mutation after-load-sym}])
  ```

  Of course, you can (and *should*) use syntax quoting to embed a query from (om/get-query) so that normalization works,
  and the post-mutation can be used to deal with the fact that other parts of the UI may want to, for example, point
  to this newly-loaded thing. The `after-load-sym` is a symbol (e.g. dispatch key to the mutate multimethod). The multi-method
  is guaranteed to be called with the app state in the environment, but no other Om environment items are ensured at
  the moment.

  ### Writing Server Mutations

  Server-side mutations in Untangled are written the same way as in Om. A mutation returns a map with a key `:action`
  and a function of no variables as a value. That function then creates, updates, and/or deletes data from the data
  storage passed in the `env` parameter to the mutation.

  #### New item creation – Temporary IDs

  Om has a built in function `tempid` that will generate an om-specific temporary ID. This allows the normalization
  and denormalization of the client side database to continue working while the server processes the new data and returns
  the permanent identifiers.

  WARNING: Because om mutations can be called multiple times (at least once and once per each remote),
   you should take care to not call `om.next/tempid` inside your mutation.
   Instead call it from your UI code that builds the mutation params, thereby solving this problem.

  Here are the client-side and server-side implementations of the same mutation:

  ```
  ;; client
  (require [untangled.client.mutations :refer [mutate]])

  (defmethod mutate 'item/new [{:keys [state]} _ {:keys [tempid text]}]
    {:remote true
     :action (fn [] (swap! state assoc-in [:item/by-id tempid] {:db/id tempid :item/text text})))})
  ```

  ```
  (defmulti server-mutate)

  (defmethod server-mutate 'item/new [{:keys [database]} _ {:keys [tempid text]}]
    {:action (fn []
              (let [database-tempid (make-database-tempid)
                    database-id (add-item-to-database database {:db/id database-tempid :item/text text})]

                {:tempids {tempid database-id}})))
  ```

  Assuming that `server-mutate` is specified as the mutation function for your server-side parser, the tempid remaps
  built on the server will be passed back to the client, where Untangled will do a recursive walk of the client-side
  database to replace every instance of the tempid with the database id. Note that the map at the :tempids key can have
  more than one tempid to database-id pair.

  #### Dealing with Server Mutation Return Values

  The server mutation is allowed to return a value. Normally the only value that makes sense is for temporary ID remapping as
  previoiusly discussed:

  ```
  {:tempids {old-id new-id}}
  ```

  which is handled for you automatically. In some cases you'd like to return other details. The Om/Untangled model
  treats all returned data from the server as novelty to be merged to your application state. With query results this
  is straightforward because the query you sent allows the merge system to properly normalize the result. Mutations
  do not have a query, so any additional data they return makes no sense by default, and such data is normally just ignored by
  the client.

  If you want to see the returned values from the server, then you need to add a `mutation-merge` function to your
  client. You can do this when you create your client:

  ```
  (defmulti return-merge (fn [state mutation-sym return-value] mutation-sym))
  (defmethod return-merge :default [s m r] s)

  (new-untangled-client :mutation-merge return-merge)
  ```

  Typically you'll create something like a multimethod for this merge function and dispatch on the mutation symbol. Then
  you can co-locate your return value merging definitions with your local mutation defitions. For example:

  ```
  (defmethod m/mutate 'some-mutation [env k p] {:remote true })
  (defmethod app/return-merge 'some-mutation [state k returnval] state')
  ```

  Note that the API is a bit different between the two: mutations get the app state atom in an environment, and you
  end up swapping on that atom to change state. The return merge function is running during state merge, and must
  take the old state and return a new state.

  ### Global network activity marker

  Untangled will automatically maintain a global network activity marker at the top level of the app state under the
  keyword `:ui/loading-data`. This key will have a `true` value when there are network requests awaiting a response from
  the server, and will have a `false` value when there are no network requests in process.

  You can access this marker from any component that composes to root by including a link in your component's query:

  ```
  (defui Item ... )
  (def ui-item (om/factory Item {:keyfn :id}))

  (defui List
    static om/IQuery (query [this] [:id :title {:items (om/get-query Item)} [:ui/loading-data '_]]
    ...
    (render [this]
      (let [{:keys [title items ui/loading-data]} (om/props this)]
        (if (and loading-data (empty? items))
          (dom/div nil \"Loading...\")
          (dom/div nil
            (dom/h1 nil title)
            (map ui-item items))))
  ```
  Because the global loading marker is at the top level of the application state, do not use the keyword as a follow-on
  read to mutations because it may unnecessarily trigger a re-render of the entire application.

  ## Differences from stock Om (Next)

  For those that are used to Om, you may be interested in the differences and rationale behind the way Untangled
  handles server interactions, particularly remote reads. There is no Om parser to do remote reads on the client side.

  In Om, you have a fully customizable experience for reads/writes; however, to get this power you must write
  a parser to process the queries and mutations, including analyzing application state to figure out when to talk
  to a remote. While this is fully general and quite flexible (Untangled is implemented on top of it, after all),
  there is a lot of head scratching to get the result you want.
  Our realization when building Untangled was that remote reads happen in two basic cases: initial load (based on
  initial application state) and event-driven load (e.g. routing, \"load comments\", etc). Then we had a few more
  facts that we threw into the mix:

  - We very often wanted to morph the UI query in some way before sending it to the server (e.g. process-roots or
  asking for a collection, but then wanting to query it in the UI \"grouped by category\").
      - This need to modify the query (or write server code that could handle various different configurations of the UI)
        led us to the realization that we really wanted a table in our app database on canonical data, and \"views\" of
        that data (e.g. a sorted page of it, items grouped by category, etc.) While you can do this with the parser, it
        is crazy complicated compared to the simple idea: Any time you load data into a given table, allow the user to
        regenerate derived views in the app state, so that the UI queries just naturally work without parsing logic for
        *each* re-render. The con, of course, is that you have to keep the derived \"views\" up to date, but this is
        much easier to reason about (and codify into a central update function) in practice than a parser.
  - We always needed a marker in the app state so that our remote parsing code could *decide* to return a remote query.
      - The marker essentially needed to be a state machine kind of state marker (ready to load, loading in progress,
        loading failed, data present). This was a complication that would be repeated over and over.
  - We often wanted a *global* marker to indicate when network activity was going on

  By eliminating the need for an Om parser to process all of this and centralizing the logic to a core set of functions
  that handle all of these concerns you gain a lot of simplicity.

  ### Differences in Initial load

  In Om you'd write a parser, set some initial state indicating 'I need to load this', and in your parser you'd return
  a remote `true` for that portion of the read when you hit it. The intention would then be that the server returning
  data would overwrite that marker and the resulting re-render would update the UI. If your UI query doesn't match what
  your server wants to hear, then you either write multiple UI-handling bits on the server, or you pre/post process the
  ROOT-centric query in your send function. Basically, you write a lot of plumbing. Server error handling is completely
  up to you in your send method.

  In Untangled, initial load is an explicit step. You simply put calls to `load` in your app start callback.

")
