(ns fulcro-devguide.A-Quick-Tour
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.client :as fc]
            [fulcro.server :as server]
            [fulcro.client.network :as fcn]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.client.logging :as log]
            [fulcro.client.data-fetch :as df]))

(defsc Counter [this                                        ; the react component itself
                {:keys [counter/id counter/n] :as props}    ; What did the query find in the database?
                {:keys [onClick] :as computed}]             ; What came in from the parent as a generated things? (e.g. callbacks)
  {:initial-state (fn [{:keys [id start]
                        :or   {id 1 start 1}
                        :as   params}] {:counter/id id :counter/n start}) ; What should go in the database on application start-up?
   :query         [:counter/id :counter/n]                  ; What does this component need from the database (relative to its entity)
   :ident         [:counter/by-id :counter/id]}             ; Where is it stored in the database (table and ID)?
  (dom/div #js {:className "counter"}
    (dom/span #js {:className "counter-label"}
      (str "Current count for counter " id ":  "))
    (dom/span #js {:className "counter-value"} n)
    (dom/button #js {:onClick #(onClick id)} "Increment")))

(def ui-counter (prim/factory Counter {:keyfn :counter/id}))

(defn abstract-increment-counter
  "Increment a counter abstraction as a plain informational map."
  [counter]
  (update counter :counter/n inc))

(defn increment-counter*
  "Increment a counter with ID counter-id in a Fulcro database."
  [database counter-id]
  (update-in database [:counter/by-id counter-id :counter/n] inc))

(defmutation ^:intern increment-counter [{:keys [id] :as params}]
  ; The local thing to do
  (action [{:keys [state] :as env}]
    (swap! state increment-counter* id))
  ; The remote thing to do. True means "the same (abstract) thing". False (or omitting it) means "nothing"
  (remote [env] true))

(defsc CounterPanel [this {:keys [counters]}]
  {:initial-state (fn [params] {:counters [(prim/get-initial-state Counter {:id 1 :start 1})]}) ;start out with 1 counter. Compose state from Counter
   :query         [{:counters (prim/get-query Counter)}]    ; Ask for counters, relative to CounterPanel
   :ident         (fn [] [:panels/by-kw :counter])}         ; Using a lambda, so we can "calculate" our database location (and make it constant)
  (let [click-callback (fn [id] (prim/transact! this
                                  `[(increment-counter {:id ~id}) :counter/by-id]))]
    (dom/div nil
      ; embedded style: kind of silly in a real app, but doable
      (dom/style nil ".counter { width: 400px; padding-bottom: 20px; }
                        button { margin-left: 10px; }")
      ; computed lets us pass calculated data to our component's 3rd argument. It has to be
      ; combined into a single argument or the factory would not be React-compatible (not would it be able to handle
      ; children).
      (map #(ui-counter (prim/computed % {:onClick click-callback})) counters))))

(def ui-counter-panel (prim/factory CounterPanel))

(defsc CounterSum [this {counter-table :counter/by-id}]     ; destructure the result into a nice name
  {:initial-state (fn [params] {})                          ; components have to have some state in the database, or queries cannot run against them
   :query         (fn [] [[:counter/by-id '_]])}            ; query against the "root" node. Get's the entire table of counters.
  (let [total (reduce (fn [total c] (+ total (:counter/n c))) 0 (vals counter-table))]
    (dom/div nil
      (str "Grand total: " total))))

(def ui-counter-sum (prim/factory CounterSum))

(defsc Root [this {:keys [ui/loading-data counter-sum panel]}]
  {:initial-state (fn [params] {:panel (prim/get-initial-state CounterPanel {})}) ; compose in the initial state for the counter panel
   :query         [:ui/loading-data                         ; this is auto-populated with a network activity indicator
                   {:panel (prim/get-query CounterPanel)}   ; compose in our child
                   {:counter-sum (prim/get-query CounterSum)}]} ; described soon...
  (dom/div nil
    (when loading-data
      (dom/span #js {:style #js {:float "right"}} "Loading..."))
    (ui-counter-panel panel)
    (ui-counter-sum counter-sum)))

;; Simulated Server

; Servers could keep state in RAM
(defonce server-state (atom {:THIS_IS_SERVER_STATE true
                             :counters             {1 {:counter/id 1 :counter/n 44}
                                                    2 {:counter/id 2 :counter/n 23}
                                                    3 {:counter/id 3 :counter/n 99}}}))

; The server queries are handled by returning a map with a :value key, which will be placed in the appropriate
; response format
(server/defquery-root :all-counters
  (value [env params]
    (log/info "SERVER query for all-counters")
    (-> (get @server-state :counters) vals vec)))

(server/defmutation ^{:intern increment-counter-on-server} increment-counter [{:keys [id]}]
  (action [env]
    (log/info "SERVER mutation increment-counter " id)
    (swap! server-state update-in [:counters id :counter/n] inc)
    nil))

; query parser. Calls read/write handlers with keywords from the query
(def server-parser (server/fulcro-parser))

; Simulated server. You'd never write this part
(defn server [env tx]
  (server-parser (assoc env :state server-state) tx))

; Networking that pretends to talk to server. You'd never write this part
(defrecord MockNetwork []
  fcn/FulcroNetwork
  (send [this edn ok err]
    (let [resp (server {} edn)]
      ; simulates a network delay:
      (js/setTimeout #(ok resp) 700)))
  (start [this] this))

(defcard-doc
  "# Quick Tour

  Fulcro is meant to be as simple as possible, but as Rich Hickey would tell you:
  simple does not mean easy. Fortunately, hard vs. easy is something you can fix just by learning. English is easy
  for me (and probably you, since you're reading this guide), but it is hardly simple (lacking in complexity). However,
  study it long enough and it becomes easier.

  Software is rife with incidental complexities that are brought on by poor overall design, paltry language features (e.g. only classes),
  side-effects, and in-place mutations.

  Fulcro takes a very progressive approach to web development to minimize these while still providing what you need to do your job. Other
  libraries are primarily concerned with the UI, and they leave the data model and server interaction up to you. We think
  this is a big mistake. The rapid start-up leads developers down an ad-hoc path that typically results in a very hard to
  navigate and reason-about code base.

  Fulcro requires you follow some rules and learn a new way of doing things. This is *by far* the most difficult part of adapting to Fulcro,
  but we hope you stick with it long enough to see that it is worth it!

  In this quick tour our intention is to show you a full-stack Fulcro application. We hope that as you read this Quick Start you will
  start to comprehend how *simple* the resulting structure is:

  - No controllers are needed, ever.
  - Networking becomes mostly transparent, and gives a *synchronous* reasoning model (by default)
  - Server and client code structure are identical. In fact, this tour leverages this fact to simulate server code in
  the browser that is identical to what you'd *put* on the server.
  - The reasoning at the UI layer can be completely local.
  - The reasoning at the model layer can be completely local.
  - The render refresh story is mostly automatic, and where it isn't, it
  is completely abstracted to the concepts in your data model, and away from the (possibly refactored) UI structure.
  - Refactoring UI becomes a much simpler task. In fact, picking up any part of your application and embedding it in a
  devcard become almost trivial!

  If you're coming from Om Next, you should know that while Fulcro inherits a lot of goodness and shares a lot of similarities,
  it is a different tool.
  It provides concrete implementations of all of the artifacts you'd normally have to write to make a full-stack
  Om Next application. It does dynamic queries differently (and in a way that is serializable, and actually navigable in time).

  ## Fulcro can use Stock React components

  It is a major strength that Fulcro integrates with stock React components (i.e. you can use them from within your code,
  and you can technically export your components to be used by external react components). So, if you already
  understand React then you have a head start. If you know nothing of React, you should eventually
  read more about it. For now, we'll cover everything you need to know.

  ## The Rendering Model

  Fulcro recommends you treat the UI as a pure function that, given the state of the application 'right now',
  can be re-rendered on each frame (change of state).

  ```
  App state -> render -> DOM
  ```

  This is a key part of the simplification: You don't have to worry how to keep bits of UI up-to-date.

  There is a key difference that users of other frameworks might miss here: not only do you *not* have to figure out
  the complexities of keeping the DOM up to date: there is no embedded in-place mutation or hidden local state to trip you up
  (unless you add it, which we don't advise except in animations).
  As you move from state to state (a sequence of app states, each of which is itself immutable) you can pretend
  as if render re-creates the entire DOM. This means you can test and reason about your UI in isolation from all
  other logic! It also means that cool features like time travel and support viewers for in-the-field diagnostics are
  easy to obtain (our support viewer for viewing a user's history is less than 100 lines of logic).

  ## Core Model Concepts

  Fulcro uses a very simple database format for the application state in the browser. Basically
  this format is a normalized graph database made of maps and vectors. Anything you show on the UI is structured into
  this database, and can be queried to create arbitrary UI trees for display.

  A given item in this database can be shown in as many places on the UI as makes sense. The fact that the database
  is *normalized* means that changing the item once in the database will result in being able to refresh all displayed versions
  easily.

  The model manipulation thus maintains the desired local reasoning model (of entries in database tables). You write
  functions that know how to manipulate specific 'kinds' of things in the database, and think nothing of the UI.

  ### Entities are just Maps

  The first central fact is that entities in your graph database are just maps.
  For example, we might choose to represent a counter with this data structure:

  ```
  { :counter/id 1 :counter/n 22 }
  ```

  which means we can write simple functions to manipulate counters:

  "
  (dc/mkdn-pprint-source abstract-increment-counter)
  "

  and think about that counter as a complete abstract thing. This gives us pretty good local reasoning.

  ### Entities go in Tables

  Entities should have an ID of some sort, at which point they are normalized into \"tables\" in the graph database. The
  database itself is just a map, and the tables are *top-level* keys in that map:

  ```
  ; KIND/INDEXED-BY  K          VALUE
  { :counter/by-id { 1 { :counter/id 1 :counter/n 1 }
                     2 { :counter/id 2 :counter/n 9 }
                     ...}}
  ```

  A table is just an entry in the database (map) that, by convention, is keyed with a keyword whose namespace indicates
  the *kind* of thing in the table, and whose name indicates how it is indexed. The k-v pairs in the table are the keys
  (of the implied index) and the values of the actual data.

  ### Changing any Entity is a Swap/Update-In

  The general app state is held in a top-level atom. So, updating any object in the database generally takes the
  form:

  ```
  (swap! app-state-atom update-in [:table/key id] increment-counter)
  ```

  Since we probably know the table name we chose with respect to the abstraction, it makes a bit more sense
  to define our object manipulations in terms of the database as opposed to the object, so our abstract
  increment becomes this:

  "
  (dc/mkdn-pprint-source increment-counter*)
  "

  which lets us write a much more concise (but equivalent) operation:

  ```
  (swap! app-state-atom increment-counter* 1)
  ```

  We were careful to augment the name with an asterisk because this will end up being an internal detail that the UI
  should not be aware of. Modifications to application state happen through a general transaction mechanism.

  ## Mutations as Abstract Transactions

  In Fulcro, you don't do model twiddling on the UI. There is a clear separation of concerns for several good
  reasons:

  - It generally pays not to mix your low-level logic with the UI.
  - The concept of an abstract mutation can isolate the UI from networking, server interactions, and async thinking.
  - Abstract mutations give nice auditing and comprehension on both client and server.
  - It turns the operation into *data*, which is easy to serialize, store, transmit, etc. This has a lot of practical applications.

  The main UI entry point for affecting a change is the `prim/transact!` function. This function lets you submit
  an abstract sequence of operations that you'd like to accomplish, and isolates the UI author from the details of
  implementing that behavior (and of even having to know things like 'will that happen locally or on the server?').

  The concrete artifacts you'll see in Fulcro code are the invocation of the `transact!`, and the implementation
  of the operations listed in the transaction:

  ```
  (prim/transact! this `[(increment-counter {:id ~id})])
  ```

  in the above transaction, we must use Clojure syntax quoting so that we can list an abstract mutation (which *looks like*
  a function call, but is not) and parameters that themselves are derived from the environment (in this case
  an id). If you're not a Clojure(script) programmer, we understand that the above expression might look a little scary. The
  '&grave;' means 'treat the following thing literally', and the '~' means 'don't treat this thing literally'. It's a way of
  keeping the compiler from treating the increment as a real function while still being able to embed `id` from the local
  execution environment. Our goal is to be able to *record* what is going on, thus we want mutations to start out as
  *pure data*.

  Mutation handlers are created with `defmutation` (ignore the `^:intern` bit, it simply enables us to embed it in this document from source code):

  "
  (dc/mkdn-pprint-source increment-counter)
  "

  That's it! You just tell it what to do locally against an atom that is passed to you, and can optionally tell it what
  to do with respect to one or more remote servers.

  Notice that interacting with your server is typically just the inclusion of a boolean true on the remote section,
  and then write a similar function on the server that has an `action` to affect the change on the server!

  Note: mutations are namespaced. So you must use namespaces on the mutations when you invoke them via transact.

  ### Why the Indirection???

  At first this seems like a bit of overkill on the boilerplate (why not just use a function???) until you realize that
  there are a number of things being handled here:

  - Sychronous *reasoning* about most aspects of the application.
  - Local optimistic (immediate) updates (through `action`)
  - Automatically plumbed server interactions (triggered through `:remote` at transaction time)
  - Use of the same abstract symbol for the operation on the client and server. The UI need not know what is local vs remote.
  - Operations in the UI and on the server are identical in appearance.
  - Consistent implementation pattern for model manipulations on the client and server.
  - The operations 'on the wire' read like abstract function calls, and lead to easy auditing and reasoning, or
  even easy-to-implement CQRS architectures. They can also be persisted in any way you please.

  NOTE: Mutations are actually implemented with a multimethod. You can work with the multimethod directly, but
  in general we recommend `defmutation`.

  ## An Example

  Let's write a simple application that shows counters that can be incremented. Each counter has an ID and a value (n).

  ### Making a Counter component

  Here's the Fulcro implementation of that UI component:

  "
  (dc/mkdn-pprint-source Counter)
  "

  The `defsc` macro means \"Define a Stateful Component\". Its syntax is intentionally patterned after function notation
  in order to get nice integration with the Cursive IDE (which can do nice processing of macros that match existing forms).

  The macro defines a React class, and augments it with Fulcro's special abilities. Technically the resulting class is
  100% compatible with plain React, but the extra information lets it function within Fulcro's data-driven infrastructure.

  The parameter list after the class name is a set of options. It is optional, but these describe how the component
  will work with respect to the database.

  - `:initial-state` - A lambda or map. If a lambda it receives parameters (you define). Must return a map of data in the correct
  format for the entity.
  - `:query` - A lambda or vector. This is a query, *relative* to the entity in the database. E.g. it is a list of things
  that are in the entity that you have interest in. This is where data-driven happens. The entity might have 100's of props, but you
  only need 2.
  - `:ident` - This tells Fulcro where the entity should live in the database. It is a vector of `KIND` and `ID`. `KIND` MUST be
  a keyword.

  The body of the `defsc` should end with a single React element (which can have children). This is a normal React rule. You
  should think of this as pure rendering logic, which is based on the properties of the entity itself.

  The database data comes in through the second argument (at the top), and any parent-calculated values (like callbacks) come
  in through the third.

  Since `defsc` generates a React Component (class). In order to render an instance of a Counter, we must make a React element
  factory:

  "
  (dc/mkdn-pprint-source ui-counter)
  "

  If more than one of something can appear in the UI as siblings, you must specify a `:keyfn` that helps React
  distinguish the DOM elements. This is just a function from database props (that you've queried for) to a distinct value. Typically
  this can just be the keyword that goes with the ID of your entity.

  ### Combining Counters into a Panel

  Lets assume we want to display some arbitrary number of counters together in a panel. We can encode that (and the
  element factory) as:

  "
  (dc/mkdn-pprint-source CounterPanel)
  (dc/mkdn-pprint-source ui-counter-panel)
  "

  This time the query contains a map.
  You can read this query as 'Query for :counters, which is a JOIN on Counter'. Note that since initial state is setting up a vector
  of counters, the implied result will be a to-many JOIN. The cardinality of JOINS is always derived from the structure
  of the live database, NOT the query.

  Note the *composition* of initial state and the query. The initial state provides an initial model
  of what should be in the database *for the component at hand*, and the query indicates which bits of that state are
  required for that same local component. Things are assembled by joining them together from level to level of the
  UI tree.

  The join is necessary because this component will be rendering Counter components. The panel should not need to
  know anything concrete about the implementation of Counter: only that it has a query, initial state, and a way
  to render. Thus, the composition uses `get-*` functions to pull that information for the child.

  The render is very similar to Counter's. The queried data will appear in props: pull it out, and
  then render it. Note the use of `ui-counter`, which is the DOM element factory for `Counter`.

  Finally, we include a callback. The counter has the 'Increment' button, but we've connected that from the
  counter UI back through to an implementation of a callback via 'computed' properties. This is a common pattern. In
  this simple example the callback structure was added to demonstrate what it looks like. One could argue that the
  counter could update itself (while maintaining local reasoning). We'll have more to say about that later.

  The implementation of the `increment-counter` mutation was shown earlier.

  There are many important points to make here, but we would like to stress the fact that the UI has no idea
  *how* counters are implemented, *if* that logic includes server interactions, etc. There are no nested callbacks
  of callbacks, no XhrIO mixed into the UI, and interestingly: no need for controller at all!

  A simple pattern of updating an abstraction in a database is all you need.

  There is one exception: That weird inclusion of `:counter/by-id` in the transaction. We'll explain that shortly.

  ### Adding the Panel to a Root element

  Fulcro UI (and DOM UI in general) forms a tree. All trees must eventually have a root.

  The UI composition (and the composition of queries and initial state) must continue to your UI Root. So, in our case
  our root looks like this:

  "
  (dc/mkdn-pprint-source Root)
  "

  The initial state is a composition again, as is the query. We add in two additional things here:

  - `:ui/loading-data` is a special key, available at the root, that is true when data is loading over the network
  - We compose in another component we've yet to discuss (which sums all counters). We'll talk more about that shortly

  There should be no surprises here. It is the same pattern all over again, except Root does cannot have an `ident`, since
  it represents the root node of the database itself. The data for root can just be a standalone entries at the top of the
  database.

  ### Starting up the App

  Normally you create a Fulcro application and mount it on a DIV in your real HTML DOM. We'll cover that later in the
  guide. Here we're in devcards and we have a helper to do that. It accepts the same basic parameters as
  `new-fulcro-client`, but mounts the resulting application in a devcard:

  ```
  (defcard-fulcro SampleApp
     Root
     {}
     {:fulcro { :started-callback (fn [app]
                                        (log/info \"Application (re)started\")
                                        (df/load app :all-counters Counter {:target [:panels/by-kw :counter :counters]}))}})
  ```

  In this quick tour we're faking the networking so you can easily explore what the server and client look like in a
  single file.

  The `:started-callback` is triggered as the application loads. This is where you can use Fulcro's networking layer
  to pre-fetch data from the server. In our case, we want to get all of the counters. Note that we can (and should) use UI
  queries. This triggers auto-normalization of the response. The `:target` option indicates that the loaded data should
  be placed in the database at the given path (usually 1 to 3 keywords in the vector, since the db is normalized). In
  this case the ident of the target panel gives the location of the component that wants the counters, and the `:counters`
  property of that component is the field under which they should be. Therefore the desired target path is the ident + the property
  as a vector.

  You'll need to understand a bit more about the structure of the database to completely understand this code, but you
  see it is really quite simple: pull `:all-counters` from the server-side database, and put them on the panel according to the
  location (known via ident) of the panel data in the database.

  ### Server Implementation

  We're representing our server database as an in-RAM atom:

  "
  (dc/mkdn-pprint-source server-state)
  "

  #### Server Queries

  To respond to the `:all-counters` query, we can use a server macro that defines handlers for top-level query properties.
  In this case we're querying from the root, so we use:

  ```
  (server/defquery-root :all-counters
    (value [env params]
      (log/info \"SERVER query for all-counters\")
      (-> server-state deref :counters vals vec)))
  ```

  That's it! No plumbing. Just return the data that was asked for. The real query is in `env`, so in a real server you
  would do the right data-driven thing and parse it to return just what the client wants.

  #### Server Mutations

  We mentioned earlier that turning a client mutation into something that also happens on the server is as simple as
  adding a `(remote [env] true)` section to the mutation.

  The server code is equally trivial (again ignore the `:intern` stuff):

  "
  (dc/mkdn-pprint-source increment-counter-on-server)
  "

  The full-stack app is running in the card below:
  ")

(defcard mock-server-state
  (dom/div nil "The state shown below is the active state of the mock server for the FinalResult card.")
  server-state
  {:watch-atom   true
   :inspect-data true})

(defcard-fulcro FinalResult
  "Below is the final result of the above application, complete with faked server interactions (we use
  setTimeout to fake network latency). If you reload this page and jump to the bottom, you'll see the initial
  server loading. (If you see an error related to mounting a DOM node, try reloading the page). You can
  see the mocked server processing take place in a delayed fashion in the Javascript Console of your
  browser.

  NOTE: The map shown in the card above this one is our simulated server state. Note how, if you click rapidly on
  increment, that the server state lags behind (because of our simulated delay). You can see how the UI
  remains responsive even though the server is lagging."
  Root
  {}
  {:inspect-data true
   :fulcro       {:started-callback (fn [app]
                                      (log/info "Application (re)started")
                                      (df/load app :all-counters Counter {:target [:panels/by-kw :counter :counters]}))
                  :networking       (map->MockNetwork {})}})

(defcard-doc
  "### The Grand Total

  You'll notice that there is a part of the UI that is tracking the grand total of the counters. This component shows
  a few more interesting features: Rendering derived values, querying entire tables, and refreshing derived UI.

  This is the implementation of the counter UI:
  "
  (dc/mkdn-pprint-source CounterSum)
  "
  The query is interesting. Queries are vectors of things you want. When one of those things is a two-element vector
  it knows you want an entry in a table (e.g. [:counter/by-id 3]). If you use the special symbol `'_` for the ID part
  then you're asking for an entire top-level entry (in this case a whole table).

  Thus we can easily use that to calculate our desired UI.

  ### UI Refresh

  It turns out that we have a problem: Nothing in Fulcro will trigger the `CounterSum` component to refresh
  when an individual counter changes! The mutations are abstract, and even though you can imagine that the UI refresh is
  a pure function that recreates the DOM, it is in fact optimized to only refresh the bits that are known to have changed. Since the
  data that changed wasn't a counter, we (accidentally) short-circuit the sum for efficiency.

  But this is where we can again leverage the data-driven model. From a local-reasoning standpoint, it would make little
  sense if you had to say which components on the UI had to refresh. What you'd rather have is something data-centric:
  \"please refresh anything that is using X\".

  This is the point of the earlier mysterious `:counter/by-id` in the `transact!`. Any keyword (or ident) that appears in a transaction
  will cause a re-render of any component that *queried* for that data (in our case `CounterSum`). These
  are called 'follow-on reads', to imply that 'anything on the screen that queries for the given property should be
  asked to re-read it (and implicitly, this means it will re-render if the value has changed)'.

  The explicit rules of refresh are:

  - Re-render the subtree that runs a transaction (short circuiting any render where data has not changed)
  - Re-render any components that queried for data that was mentioned in follow-on reads
  - Re-render any components that queried for data that is known to have changed in a mutation (declarative refresh)

  You've seen the first two. The latter one lets you declare the list of things that have changed on the mutation itself.
  Follow-on reads work in all cases, even when there is some derived data that was contrived into the UI (by reading
  a table, for example). The declarative refresh is useful in ensuring UI refresh is mostly automatic. It looks like
  this:

  ```
  (defmutation do-something [{:keys [id]}] ; id is in scope for all sections
    (action [env] ...local action...) ; as before
    (refresh [env] [:some-prop [:table/by-id id])) ; refresh any UI component that queries for :some-prop or has the given identity
  ```

  You can, of course, play with the source code of this example in the devcards.

  We certainly hope you will continue reading into the details of all of these features. You should start with the
  [next chapter](#!/fulcro_devguide.B_UI) on UI.
  ")
