(ns fulcro-devguide.H-Server-Interactions
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.mutations :as m]
            [fulcro.client :as fc]))

; TODO: (advanced) cookies/headers/augmented response

(defui ^:once CategoryQuery
  static prim/IQuery
  (query [this] [:db/id :category/name])
  static prim/Ident
  (ident [this props] [:categories/by-id (:db/id props)]))

(defui ^:once ItemQuery
  static prim/IQuery
  (query [this] [:db/id {:item/category (prim/get-query CategoryQuery)} :item/name])
  static prim/Ident
  (ident [this props] [:items/by-id (:db/id props)]))

(def sample-server-response
  {:all-items [{:db/id 5 :item/name "item-42" :item/category {:db/id 1 :category/name "A"}}
               {:db/id 6 :item/name "item-92" :item/category {:db/id 1 :category/name "A"}}
               {:db/id 7 :item/name "item-32" :item/category {:db/id 1 :category/name "A"}}
               {:db/id 8 :item/name "item-52" :item/category {:db/id 2 :category/name "B"}}]})

(def component-query [{:all-items (prim/get-query ItemQuery)}])

(def hand-written-query [{:all-items [:db/id :item/name
                                      {:item/category [:db/id :category/name]}]}])

(defui ^:once ToolbarItem
  static prim/IQuery
  (query [this] [:db/id :item/name])
  static prim/Ident
  (ident [this props] [:items/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [item/name]} (prim/props this)]
      (dom/li nil name))))

(def ui-toolbar-item (prim/factory ToolbarItem {:keyfn :db/id}))

(defui ^:once ToolbarCategory
  static prim/IQuery
  (query [this] [:db/id :category/name {:category/items (prim/get-query ToolbarItem)}])
  static prim/Ident
  (ident [this props] [:categories/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [category/name category/items]} (prim/props this)]
      (dom/li nil
        name
        (dom/ul nil
          (map ui-toolbar-item items))))))

(def ui-toolbar-category (prim/factory ToolbarCategory {:keyfn :db/id}))

(defui ^:once Toolbar
  static prim/IQuery
  (query [this] [{:toolbar/categories (prim/get-query ToolbarCategory)}])
  Object
  (render [this]
    (let [{:keys [toolbar/categories]} (prim/props this)]
      (dom/div nil
        (dom/button #js {:onClick #(prim/transact! this '[(server-interaction/group-items)])} "Trigger Post Mutation")
        (dom/button #js {:onClick #(prim/transact! this '[(server-interaction/group-items-reset)])} "Reset")
        (dom/ul nil
          (map ui-toolbar-category categories))))))

(defmethod m/mutate 'server-interaction/group-items-reset [{:keys [state]} k p]
  {:action (fn [] (reset! state (prim/tree->db component-query sample-server-response true)))})

(defn add-to-category
  "Returns a new db with the given item added into that item's category."
  [db item]
  (let [category-ident (:item/category item)
        item-location  (conj category-ident :category/items)]
    (update-in db item-location (fnil conj []) (prim/ident ItemQuery item))))

(defn group-items
  "Returns a new db with all of the items sorted by name and grouped into their categories."
  [db]
  (let [sorted-items   (->> db :items/by-id vals (sort-by :item/name))
        category-ids   (-> db (:categories/by-id) keys)
        clear-items    (fn [db id] (assoc-in db [:categories/by-id id :category/items] []))
        db             (reduce clear-items db category-ids)
        db             (reduce add-to-category db sorted-items)
        all-categories (->> db :categories/by-id vals (mapv #(prim/ident CategoryQuery %)))]
    (assoc db :toolbar/categories all-categories)))


(defmethod m/mutate 'server-interaction/group-items [{:keys [state]} k p]
  {:action (fn [] (swap! state group-items))})

(defcard-doc
  "
  # Server interaction

  Server request processing in Fulcro has the following features:

  - Networking is provided, including encoding/decoding EDN on the wire (via transit)
  - All network requests (queries and mutations) are processed sequentially (unless you specify otherwise). This allows you
  to reason about optimistic updates (Starting more than one at a time via async calls could
  lead to out-of-order execution, and impossible-to-reason-about recovery from errors).
  - You may provide fallbacks that indicate error-handling mutations to run on failures.
  - Writes and reads that are enqueued together will always be performed in write-first order.
  - Any `:ui/` namespaced query elements are automatically elided when generating a query from the UI to a server.
  - Normalization of a remote query result is automatic.
  - Deep merge of query results uses intelligent overwrite (described below in Deep Merge).

  ## General Theory of Operation

  There are only a few general kinds of interactions with a server:

  - Initial loads when the application starts
  - Incremental loads of sub-graphs of something that was already loaded.
  - Event-based loads (e.g. user or timed events)
  - Server pushes

  In standard Fulcro networking, *all* of the above have the following similarities:

  - A component-based graph query needs to be involved (to enable auto-normalization). Even the server push (though in that case the client needs to know what **implied** question the server is sending data about.
  - The data from the server will be a tree that has the same shape as the query.
  - The data needs to be normalized into the client database.
  - Any of them *may* desire some post-load transformation to shape the result to a form the UI
  (e.g. perhaps you need to sort or paginate some list of items that came in).

  **IMPORTANT**: Remember what you learned about the graph database, queries, and idents. This section cannot possibly be understood
  properly if you do not understand those topics!

  ## The General Load Mechanism

  So, *all* data loads share this same basic pattern: There is a *query*, an incoming *tree of data*, and an existing *graph database*.

  We want to:

  1. Normalize the tree of data.
  2. Put it in the graph database.
  3. *link* it into the existing graph so that the UI query sees the desired result.

  So, let's say we want to load all of our friends from the server. A query has to be rooted somewhere, so we'll invent
  a root-level keyword, `:all-friends`, and join it to the UI query for a `Person`:

  ```
  [{:all-friends (prim/get-query Person)}]
  ```

  What we'd like to see then from the server is a return value with the shape of the query:

  ```
  { :all-frields [ {:db/id 1 ...} {:db/id 2 ...} ...] }
  ```

  The internal function `tree->db` is used to automatically turn this into the following graph database:

  ```
  { :all-frields [ [:person/by-id 1] [:person/by-id 2] ...]
    :person/by-id { 1 { :db/id 1 ...}
                    2 { :db/id 2 ...}
                    ...}}
  ```

  which looks exactly like our normal graph database format. It's just that we have a new root property called
  `:all-friends` that points to the loaded data.

  What if our existing graph database included our other UI stuff:

  ```
  { :current-screen [:screens/by-type :friends]
    ...
    :screens/by-type { :friends { :friends/list [] ... }}}
  ```

  it might be that what we'd *like* to have is our friends appear in the correct screen (at
  `[:screens/by-type :friends :friends/list]`). Note, normalization means that no fields is
  ever more than 3 levels deep in our database (ident size + 1).

  If we were to merge the two, we'd have:

  ```
  { :all-frields [ [:person/by-id 1] [:person/by-id 2] ...]  ; (1) MOVE this to (2)
    :person/by-id { 1 { :db/id 1 ...}
                    2 { :db/id 2 ...}
    :current-screen [:screens/by-type :friends]
    :screens/by-type { :friends { :friends/list [] ... }}} ; (2) where friends *should* be
  ```

  and if we were to just move the \"graph edge\" `:all-frields` from the root into the friends screen we'd have successfully
  loaded our friends onto the screen (remember, UI updates are a pure rendering of state):

  ```
  { :person/by-id { 1 { :db/id 1 ...}
                    2 { :db/id 2 ...}
    :current-screen [:screens/by-type :friends]
    :screens/by-type { :friends { :friends/list [ [:person/by-id 1] [:person/by-id 2] ...]  ... }}}
  ```

  Since the graph database is just nodes and edges, there really aren't any more operations to worry about!
  You've essentially got to *normalize a tree*, *merge normalized data*, and *move graph edges*.

  Therefore, *all* generalized integration of data into your graph database from *any* external source can be
  done the same way, and all of that is either automatic or trivial!

  ## Loading Data: `load` and `load-field`

  The main workhorses of loading in Fulcro are `load` and `load-field`. They also have counterparts (ending in `-action`)
  that can be used *inside* of your own mutations to compose optimistic updates with load triggering as a
  single abstract operation:

  - `load` and `load-action` : API for most loads.
  - `load-field` and `load-field-action` : Field-targeted API for loading a subgraph (field) starting from something you've already loaded.

  The former of each pair are methods that directly invoke a `transact!` to place load requests into a load queue. They can
  be used from the UI, lifecycle methods, timeouts, etc. But *should not* be used within mutations themselves.

  The latter of each pair are the low-level implementation methods that *can be* used to compose remote reads with your
  own mutations (e.g. you want to switch to a new area of the UI (local mutation), but also trigger a *remote read* to load the
  content of that UI at the same time).

  ### Load vs. Load Field

  The `fulcro.client.data-fetch/load` (the function) is meant for most general loading. It can load any sub-graph of your database using
  an ident or keyword as a \"root\" and an optional component's query to complete a graph query. Load can be passed
  the app, reconciler, or any component as its first argument.

  `load-field` is really just a helper for a common use-case: loading a field of some specific thing that you loaded
  previously. It focuses the component's query to the specified field, associates the component's ident with the query,
  and asks the UI to re-render all components with the calling component's ident after the load is complete. Since `load-field`
  requires a component to build the query sent to the server it must be used with a live (mounted) component that has an ident.

  ### Using Load

  The `fulcro.data-fetch/load` function is your main workhorse. Here are the most commonly used cases:

  ```
  ; Sends [{:server-keyword (prim/get-query Component)}]. Data ends up in root node of client db at :server-keyword
  (load comp-or-app :server-keyword Component)

  ; Same as above, but the resulting root edge :server-keyword will no longer appear in the client database.
  ; Instead the result will appear at the path [:table id :field]
  (load comp-or-app :server-keyword Component {:target [:table id :field]})

  ; Includes the parameters {:page 1} in the query to the server, loads results into [:table id :field],
  ; then runs the mutation '[(sort-things {:order :asc})] on the client (which can do anything to reshape the data).
  (load comp-or-app :kw Component {:params {:page 1}
                                   :target [:table id :field]
                                   :post-mutation 'sort-things
                                   :post-mutation-params {:order :asc}})

  ; Load a specific entity directly into a table:
  (load comp-or-app [:person/by-id 3] Person)
  ```

  ### Handling Loads

  The server-side processing of queries and mutations can be done in a number of ways. The next chapter will tell you
  how to set up a server, but *if* you choose to use the provided utilities and query parsing, then you will have access
  to the following *server-side* macros (in `fulcro.server`):

  ```
  (defquery-root :server-keyword
     \"optional docstring\"
     (value [env params]
        ...data...))

  (defquery-entity :person/by-id
     \"optional docstring\"
     (value [env id params]
        ...data...))

  ; also, for handling remote mutations:
  (fulcro.server/defmutation ; just like client defmutation
     \"optional docstring\"
     [params]
     (action [env] ...))
  ```

  `load` with an ident and `load-field` will end up triggering `defquery-entity`, since those queries are rooted at
  a component's ident. All other loads will be handled by `defquery-root`, since they will be rooted at a root-node
  keyword (the server knows nothing of the client's intended `:target`.

  This makes the server coding as simple as building a data structure for the query and parameters!

  ### Pruning the Query

  Sometimes your UI graph asks for things that you'd like to load incrementally. Let's say you were loading a blog
  post that has comments. Perhaps you'd like to load the comments later:

  ```
  (df/load app :server/blog Blog {:params {:id 1 }
                                  :target [:screens/by-name :blog :current-page]
                                  :without #{:blog/comments}})
  ```

  The `:without` parameter can be used to elide portions of the query (it looks recursively). The query sent to the
  server will *not* ask for `:blog/comments` (which the server has to be coded to honor).

  ### Filling in the Subgraph

  Later, say when the user scrolls to the bottom of the screen or clicks on \"show comments\", we can load the rest
  from of this previously partially-loaded graph within the Blog itself:

  ```
  (defui Blog
    static prim/Ident
    (ident [this props] [:blog/by-id (:db/id props)])
    static prim/IQuery
    (query [this] [:db/id :blog/title {:blog/content (prim/get-query BlogContent)} {:blog/comments (prim/get-query BlogComment)}])
    ...
    Object
    (render [this]
      (dom/div nil
         ...
         (dom/button #js {:onClick #(load-field this :blog/comments)} \"Show Comments\")
         ...)))
  ```

  The `load-field` function does the opposite of `:without`: it prunes everything from the query *except* for the branch
  joined through the given key. It also generates an *entity rooted query* based on the calling component's ident:

  ```
  [{[:table ID] subquery}]
  ```

  where the `[:table ID]` are the ident of the invoking component, and subquery is `(prim/get-query invoking-component)`, but
  focused down to the one field. In the example above, this would end up something like this:

  ```
  [{[:blog/by-id 1] [{:blog/comments [:db/id :comment/author :comment/body]}]}]
  ```

  #### Initial load

  In Fulcro, initial load is an explicit step. You simply put calls to `load` in your app's started callback.
  State markers are put in place that allow you to then render the fact that you are loading data. Any number of separate
  server queries can be queued, and the queries themselves are used for normalization. Post-processing of the response
  is well-defined and trivial to access.

  ```
  (fc/new-fulcro-client
    :started-callback
      (fn [{:keys [reconciler] :as app}]
        (df/load app :items CollectionComponent)))
  ```

  In the above example the client is created (which must be mounted as a separate step). Once the application is mounted 
  it will call the `:started-callback` which in turn will trigger a load.

  #### Loading a specific entity from the server

  The `load` function can accept an ident instead of a top-level property:

  ```
  (df/load app [:person/by-id 22] Person)
  ```

  which will load the specific entity (assuming your server responsds with it) into you client database. This could be
  used, for example, to refresh a particular entity in your client database.

  Giving a post mutation as a parameter would allow you to further integrate that entity into you views. Post mutations are 
  run after the query, and allow you to modify the application state as you see fit.

  NOTE: the `fulcro.core/integrate-ident!` function is particularly handy for peppering the ident of the resulting item
  around the views via a post mutation.

  #### Including parameters on the server query

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

  #### Lazy loading a field

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
     static prim/IQuery
     (query [this] [:author :text]))

  (defui Item
     static prim/IQuery
     (query [this] [:id :value {:comments (prim/get-query Comment)}])
     static prim/Ident
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

  #### More About How it Works

  The `fulcro/load` mutation does a very simple thing: It puts a state marker in a well-known location in your app state
  to indicate that you're wanting to load something (and returns `:remote true`). This causes the network
  plumbing to be triggered. The network plumbing only receives mutations that are marked remote, so it does the following:

  - It looks for the special mutations `fulcro/load` and `fulcro.client.data-fetch/fallback` (deprecated name is `tx/fallback`). The latter is part of the unhappy path handling.
     - For each load, it places a state marker in the app state at the target destination for the query data
     - All loads that are present are combined together into a single query
  - It looks for other mutations
  - It puts the 'other mutations' on the send queue (iff they are marked `:remote`)
  - It puts the derived query/queries from the `fulcro/load`s onto the send queue.

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

  Fulcro recognizes the need to separate attributes that are UI-only and those that should actually be sent to
  the server. If a component, for example, wants to query for:

  ```
  [:ui/popup-visible :db/id :item/name]
  ```

  where the `popup-visible` item is actually in app state (a useful thing to do with most state instead of making
  it component local), then you have a problem when that component is composed into another component that
  is to be used when generating a server query. You don't want the UI-specific attributes to *go* to the server!

  Fulcro handles this for you. Any attributes in your component queries that are namespaced to `ui` are automatically
  (and recursively) stripped from queries before they are sent to the server. There is nothing for you to do except
  namespace these local-only attributes in the queries! (Additionally, there are local mutation
  helpers that can be used to update these without writing custom mutation code. See the section on Mutation)

  ### Data merge

  Fulcro will automatically merge (and normalize) the result of loads into the application client database.
  It that has a number of features that are useful for simple application reasoning:

  #### Deep Merge

  Fulcro merges the response via a deep merge, meaning that existing data is not wiped out by default. Unfortunately,
  this causes a different problem. Let's say you have two UI components (with the same Ident) that ask for similar information:

  Component A asks for [:a]
  Component A2 asks for [:a :b]

  Of course, these queries are composed into a larger query, but you can imagine that if we use the query of A2, normalization
  will put something like this somewhere in the app state: `{ :a 1 :b 2}`. Now, at a later time, say we re-run a load but
  use component A's query. The response from the server will say something like `{:a 5}`, because all we asked for was
  `:a`!  But what if both A and A2 are on the screen??? Well, depending on how you merge strange things can happen.

  So, Fulcro forms an opinion on this scenario:

  - First, since it isn't a great thing to do, you should avoid it
  - However, if you do it, Fulcro merges with the following rules:
      - If the query *asks* for an attribute, and the *response does not include it*, then it is always removed from the app state since the
      server has clearly indicated it is gone.
      - If the query *does not ask* for an attribute (which means the response cannot possibly contain it), then Fulcro
      will avoid removing it, even if other attributes come back (e.g. it will be a merge leaving the property that was
      not asked for alone). This does indicate that your UI is possibly in a state inconsistent with the server, which
      is the reason for the \"avoid this case\" advice.

  #### Queries and Normalization

  Loads must use real composed queries from the UI for normalization to work, since the ident functions are found through
  metadata of that query.

  Therefore, you almost *never* want to use a hand-written query that has not been placed on a `defui`. **It is perfectly
  acceptable to define standalone queries via `defui` (non-rendering components) to ensure normalization will work**,
  and this will commonly be the case if your UI needs to ask for data in a structure different from what you want to run against the server.

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

  The resulting query, if you were to ask for it with `prim/get-query` would look the same as this hand-written
  query:

  "
  (dc/mkdn-pprint-source hand-written-query)
  "
  However, if you were to use the hand-written query then Fulcro would not know how to normalize the server result
  into your database because the Ident functions would not be known.

  The following card demonstrates the difference of merging the following server response: "
  (dc/mkdn-pprint-source sample-server-response))

(defn merge-using-query [state-atom query data]
  (let [db (prim/tree->db query data true)]
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
  and now perhaps you can see the true power of Fulcro's approach to data loading! One server query implementation
  can now serve any of your needs for displaying these items.

  #### Post Mutations

  We've seen the power of using a component to generate queries that are simple for the server (even though the UI might
  want to display the result in a more complex way). Now, we'll finish the example by showing you how post mutations
  can complete the story (and for Om Next users, complete your understanding of why a custom parser isn't necessary in Fulcro).

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

  The Fulcro post-mutation can defined as:

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

(defcard-fulcro toolbar-with-items-by-category
  "This card allows you to simulate the post-mutation defined above, and see the resulting UI and database change. The
  Reset button will restore the db to the pre-mutation state, so you can A/B compare the before and after picture."
  Toolbar
  (prim/tree->db component-query sample-server-response true)
  {:inspect-data true})

(defcard-doc "
  ### Parameterized Reads

  You may add parameters to your remote reads using an optional argument to your loads:

  ```
  (defui Article
    static prim/IQuery (query [this] [:id :content {:comments (prim/get-query Comments)}])
    static prim/Ident (ident [this props] [:article/by-id (:id props)])
    (render [this]
      ;; render article content
      ;; ...
      ;; render a load comments button:
      (dom/button #js {:onClick #(df/load-field this :comments
                                  :params {:lowValue 0 :highValue 10})}
        \"Load Comments\")))
  ```
  This sample query parameterizes the read to this article's comments with a range, so that the server can know only to return
  the ten most recent comments.

  So, `(load-field this :comments)` above would yield a query of the form:

  ```
  [{[:article/by-id 32] [{:comments [:other :props]]}]
  ```

  while `(load-field this :comments :params {:lowValue 0 :highValue 10})` would yield a query of the form:

  ```
  [{[:article/by-id 32] [({:comments [:other :props]} {:lowValue 0 :highValue 10})]}]
  ```

  So, when you specify parameters to one of the items in your query, Fulcro will add the parameters at that level
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
  to elide the portions of the query (properties/joins) that use the given keywords.

  Using `:without` lets you defer some portion of a load, and `load-field` pairs nicely because it loads just
  one part of a query. The pair give you a nice way to accomplish incremental loading of data.

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
  (fulcro.client/clear-pending-remote-requests! my-app)
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
  is guaranteed to be called with the app state in the environment, but no other environment items are ensured at
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

  Fulcro has a built in function `tempid` that will generate an om-specific temporary ID. This allows the normalization
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
        {::prim/tempids {tempid database-id}})))
  ```

  The return value of a mutation may include the special key `::prim/tempids`. This is a mapping from incoming temporary
  IDs to the real IDs they got assigned on the server.
  This will be passed back to the client, where Fulcro will *automatically* do a recursive walk of the client-side
  database to replace **every instance** of the temporary ID with the database id. Note that the map at the ::prim/tempids key can have
  more than one tempid to database-id pair.

  #### Dealing with Server Mutation Return Values

  The server mutation is allowed to return a value. Normally the only value that makes sense is for temporary ID remapping as
  previoiusly discussed, because that is automatically processed:

  ```
  {::prim/tempids {old-id new-id}}
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

  ### Global network activity marker

  Fulcro will automatically maintain a global network activity marker at the top level of the app state under the
  keyword `:ui/loading-data`. This key will have a `true` value when there are network requests awaiting a response from
  the server, and will have a `false` value when there are no network requests in process.

  You can access this marker from any component that composes to root by including a link in your component's query:

  ```
  (defui Item ... )
  (def ui-item (prim/factory Item {:keyfn :id}))

  (defui List
    static prim/IQuery (query [this] [:id :title {:items (prim/get-query Item)} [:ui/loading-data '_]]
    ...
    (render [this]
      (let [{:keys [title items ui/loading-data]} (prim/props this)]
        (if (and loading-data (empty? items))
          (dom/div nil \"Loading...\")
          (dom/div nil
            (dom/h1 nil title)
            (map ui-item items))))
  ```
  Because the global loading marker is at the top level of the application state, do not use the keyword as a follow-on
  read to mutations because it may unnecessarily trigger a re-render of the entire application.

  ## Differences from Om Next

  For those that are used to Om Next you may be interested in the differences and rationale behind the way Fulcro
  handles server interactions, particularly remote reads. There is no Om parser to do remote reads on the client side.

  In Om, you have a fully customizable experience for reads/writes; however, to get this power you must write
  a parser to process the queries and mutations, including analyzing application state to figure out when to talk
  to a remote. While this is fully general and quite flexible (Fulcro is implemented on top of it, after all),
  there is a lot of head scratching to get the result you want.
  Our realization when building Fulcro was that remote reads happen in two basic cases: initial load (based on
  initial application state) and event-driven load (e.g. routing, \"load comments\", etc). Then we had a few more
  facts that we threw into the mix:

  - We very often wanted to morph the UI query in some way before sending it to the server (e.g. process-roots or
  asking for a collection, but then wanting to query it in the UI \"grouped by category\").
      - This need to modify the query (or write server code that could handle various different configurations of the UI)
        led us to the realization that we really wanted a table in our app database on canonical data, and \"views\" of
        that data (e.g. a sorted page of it, items grouped by category, etc.) While you can do this with the parser, it
        is complicated compared to the simple idea: Any time you load data into a given table, allow the user to
        regenerate derived views in the app state, so that the UI queries just naturally work without parsing logic for
        *each* re-render. The con, of course, is that you have to keep the derived \"views\" up to date, but this is
        much easier to reason about (and codify into a central update function) in practice than a parser.
  - We always needed a marker in the app state so that our remote parsing code could *decide* to return a remote query.
      - The marker essentially needed to be a state machine kind of state marker (ready to load, loading in progress,
        loading failed, data present). This was a complication that would be repeated over and over.
  - We often wanted a *global* marker to indicate when network activity was going on
  - We wanted a better way to deal with UI refresh due to abstract data changes.

  By eliminating the need for an Om parser to process all of this and centralizing the logic to a core set of functions
  that handle all of these concerns you gain a lot of simplicity.

  ### Differences in Initial load

  In Om you'd write a parser, set some initial state indicating 'I need to load this', and in your parser you'd return
  a remote `true` for that portion of the read when you hit it. The intention would then be that the server returning
  data would overwrite that marker and the resulting re-render would update the UI. If your UI query doesn't match what
  your server wants to hear, then you either write multiple UI-handling bits on the server, or you pre/post process the
  ROOT-centric query in your send function. Basically, you write a lot of plumbing. Server error handling is completely
  up to you in your send method.

  In Fulcro, initial load is an explicit step. You simply put calls to `load` in your app start callback.
")
