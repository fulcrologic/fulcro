(ns fulcro-devguide.A-Quick-Tour
  (:require-macros [cljs.test :refer [is]]
                   [fulcro-devguide.tutmacros :refer [fulcro-app]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [fulcro.client.core :as fc]
            [fulcro.client.network :as fcn]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.mutations :as m]
            [fulcro.client.logging :as log]
            [fulcro.client.data-fetch :as df]))

(defn increment-counter [counter] (update counter :counter/n inc))

(defui ^:once Counter
  static fc/InitialAppState
  (initial-state [this {:keys [id start]
                           :or   {id 1 start 1}
                           :as   params}] {:counter/id id :counter/n start})
  static om/IQuery
  (query [this] [:counter/id :counter/n])
  static om/Ident
  (ident [this props] [:counter/by-id (:counter/id props)])
  Object
  (render [this]
    (let [{:keys [counter/id counter/n]} (om/props this)
          onClick (om/get-computed this :onClick)]
      (dom/div #js {:className "counter"}
        (dom/span #js {:className "counter-label"}
          (str "Current count for counter " id ":  "))
        (dom/span #js {:className "counter-value"} n)
        (dom/button #js {:onClick #(onClick id)} "Increment")))))

(def ui-counter (om/factory Counter {:keyfn :counter/id}))

(defmethod m/mutate 'counter/inc [{:keys [state] :as env} k {:keys [id] :as params}]
  {:remote true
   :action (fn [] (swap! state update-in [:counter/by-id id] increment-counter))})

(defui ^:once CounterPanel
  static fc/InitialAppState
  (initial-state [this params]
    {:counters [(fc/get-initial-state Counter {:id 1 :start 1})]})
  static om/IQuery
  (query [this] [{:counters (om/get-query Counter)}])
  static om/Ident
  (ident [this props] [:panels/by-kw :counter])
  Object
  (render [this]
    (let [{:keys [counters]} (om/props this)
          click-callback (fn [id] (om/transact! this
                                    `[(counter/inc {:id ~id}) :counter-sum]))]
      (dom/div nil
        ; embedded style: kind of silly in a real app, but doable
        (dom/style nil ".counter { width: 400px; padding-bottom: 20px; }
                        button { margin-left: 10px; }")
        (map #(ui-counter (om/computed % {:onClick click-callback})) counters)))))

(def ui-counter-panel (om/factory CounterPanel))

(defui ^:once CounterSum
  static fc/InitialAppState
  (initial-state [this params] {})
  static om/IQuery
  (query [this] [[:counter/by-id '_]])
  Object
  (render [this]
    (let [{:keys [counter/by-id]} (om/props this)
          total (reduce (fn [total c] (+ total (:counter/n c))) 0 (vals by-id))]
      (dom/div nil
        (str "Grand total: " total)))))

(def ui-counter-sum (om/factory CounterSum))

(defui ^:once Root
  static fc/InitialAppState
  (initial-state [this params]
    {:panel (fc/get-initial-state CounterPanel {})})
  static om/IQuery
  (query [this] [:ui/loading-data
                 {:panel (om/get-query CounterPanel)}
                 {:counter-sum (om/get-query CounterSum)}])
  Object
  (render [this]
    (let [{:keys [ui/loading-data counter-sum panel]} (om/props this)]
      (dom/div nil
        (when loading-data
          (dom/span #js {:style #js {:float "right"}} "Loading..."))
        (ui-counter-panel panel)
        (ui-counter-sum counter-sum)))))

;; Simulated Server

; Servers could keep state in RAM
(defonce server-state (atom {:THIS_IS_SERVER_STATE true
                             :counters             {1 {:counter/id 1 :counter/n 44}
                                                    2 {:counter/id 2 :counter/n 23}
                                                    3 {:counter/id 3 :counter/n 99}}}))

; The server queries are handled by returning a map with a :value key, which will be placed in the appropriate
; response format
(defn read-handler [{:keys [ast state]} k p]
  (log/info "SERVER query for " k)
  (case k
    ; When querying for :all-counters, return the complete set of values in our server counter db (atom in RAM)
    :all-counters {:value (-> (get @state :counters) vals vec)}
    nil))

; The server mutations are handled by returning a map with a :action key whose value is the function that will
; cause the change on the server
(defn write-handler [env k p]
  (log/info "SERVER mutation for " k " with params " p)
  (case k
    ; When asked to increment a counter on the server, do so by updating in-memory atom database
    'counter/inc (let [{:keys [id]} p]
                   {:action (fn []
                              (swap! server-state update-in [:counters id :counter/n] inc)
                              nil)})
    nil))

; Om Next query parser. Calls read/write handlers with keywords from the query
(def server-parser (om/parser {:read read-handler :mutate write-handler}))

; Simulated server. You'd never write this part
(defn server [env tx]
  (server-parser (assoc env :state server-state) tx))

; Networking that pretends to talk to server. You'd never write this part
(defrecord MockNetwork [complete-app]
  fcn/FulcroNetwork
  (send [this edn ok err]
    (let [resp (server {} edn)]
      ; simulates a network delay:
      (js/setTimeout #(ok resp) 700)))
  (start [this app]
    (assoc this :complete-app app)))

(defcard-doc
  "# Quick Tour

  Fulcro is meant to be as simple as possible, but as Rich Hickey would tell you:
  simple does not mean easy. Fortunately, hard vs. easy is something you can fix just by learning.

  Om Next (the lower layer of Fulcro) takes a very progressive approach
  to web development, and as such requires that you understand some new
  concepts. This is *by far* the most difficult part of adapting to Fulcro.

  In this quick tour our intention is to show you a full-stack Fulcro
  application. We hope that as you read this Quick Start you will
  start to comprehend how *simple* the resulting structure is:

  - No controllers are needed, ever.
  - Networking becomes mostly transparent, and gives a synchronous reasoning model (by default)
  - Server and client code structure are identical. In fact, this tour leverages this fact to simulate server code in
  the browser that is identical to what you'd *put* on the server.
  - The reasoning at the UI layer can be completely local.
  - The reasoning at the model layer can be completely local.
  - The render refresh story is mostly automatic, and where it isn't, it
  is completely abstracted from the UI structure to ease developer reasoning.

  If you're coming from Om Next, you should know that Fulcro is *not* a competing project. Fulcro is a set of
  thin libraries that
  provide default implementations of all of the artifacts you'd normally have to write to make a full-stack
   Om Next application. In many cases Fulcro is required to 'make a call' about how to do something.
   When it does, our documentation tries to discuss the relative merits and costs.

  ## Om Next Components can use Stock React components

  The first major strength is that Om Next (and therefore Fulcro) integrates with stock React components. So, if you already
  understand React then you already have a head start. If you know nothing of React, you should eventually
  read more about it. For now, we'll cover everything you need to know.

  ## The Rendering Model

  Fulcro recommends you treat the UI as a pure function that, given the state of the application 'right now',
  can be re-rendered on each frame (change of state).

  ```
  App state -> render -> DOM
  ```

  This is a key part of the simplification: You don't have to worry how to keep bits of UI up-to-date.

  There is a key difference that users of other frameworks might miss here: not only do you *not* have to figure out
  the complexities of keeping the DOM up to date: there is no mutation or hidden local state to trip you up.
  As you move from state to state (a sequence of app states, each of which is itself immutable) you can pretend
  as if render re-creates the entire DOM. This means you can test and reason about your UI in isolation from all
  other logic!

  ## Core Model Concepts

  Fulcro uses a very simple (default Om Next) database format for the application state in the browser. Basically
  this format is a normalized graph database made of maps and vectors. Anything you show on the UI is structured into
  this database, and can be queried to create arbitrary UI trees for display.

  A given item in this database can be shown in as many places on the UI as makes sense. The fact that the database
  is *normalized* means that changing the item once in the database will result in being able to refresh all displayed versions
  easily.

  The model manipulation thus maintains the desired local reasoning model (of entries in database tables). You write
  functions that know how to manipulate specific 'kinds' of things in the database, and think nothing of the UI.

  For example, say we have a counter that we'd like to represent with this data structure:

  ```
  { :counter/id 1 :counter/n 22 }
  ```

  We can write simple functions to manipulate counters:

  "
  (dc/mkdn-pprint-source increment-counter)
  "

  and think about that counter as a complete abstract thing (and write tests and clojure specs for it, etc.).

  The Fulcro database table for counters then looks something like this:

  ```
  { :counter/by-id { 1 { :counter/id 1 :counter/n 1 }
                     2 { :counter/id 2 :counter/n 9 }
                     ...}}
  ```

  A table is just an entry in the database (map) that, by convention, is keyed with a keyword whose  namespace indicates
  the kind of thing in the table, and whose name indicates how it is indexed. The k-v pairs in the table are the keys
  (of the implied index) and the values of the actual data.

  The general app state is held in a top-level atom. So, updating any object in the database generally takes the
  form:

  ```
  (swap! app-state-atom update-in [:table/key id] operation)
  ```

  or in our example case:

  ```
  (swap! app-state-atom update-in [:counter/by-id 1] increment-counter)
  ```

  NOTE: You still need to know *where* to put this code, and how to find/access the `app-state-atom`.

  ## Mutations as Abstract Transactions

  In Fulcro, you don't do model twiddling on the UI. There is a clear separation of concerns for several good
  reasons:

  - It generally pays not to mix your low-level logic with the UI.
  - The concept of an abstract mutation can isolate the UI from networking, server interactions, and async thinking.
  - Abstract mutations give nice auditing and comprehension on both client and server.

  The main UI entry point for affecting a change is the `om/transact!` function. This function lets you submit
  an abstract sequence of operations that you'd like to accomplish, and isolates the UI author from the details of
  implementing that behavior (and of even having to know things like 'will that happen locally or on the server?').

  The concrete artifacts you'll see in Fulcro code are the invocation of the `transact!`, and the implementation
  of the operations listed in the transaction:

  ```
  (om/transact! this `[(counter/inc {:id ~id})])
  ```

  in the above transaction, we must use Clojure syntax quoting so that we can list an abstract mutation (which looks like
  a function call, but is not) and parameters that themselves are derived from the environment (in this case
  an id). If you're not a Clojure(script) programmer, we understand that the above expression looks a little scary. The
  '&grave;' means 'treat the following thing literally', and the '~' means 'don't treat this thing literally'. It's a way of keeping the compiler from treating the increment as a function while still being able to embed `id` from the local
  execution environment.

  The concrete implementation of the mutation on the model side looks like this:

  ```
  (defmethod m/mutate 'counter/inc [{:keys [state] :as env} k {:keys [id] :as params}]
    { :action
        (fn []
          (swap! state update-in [:counter/by-id id] increment-counter))})
  ```

  This looks a little hairy at first until you notice the primary guts are just what we talked about earlier in
  the model manipulation: it's just an `update-in`.

  The wrapping is a pretty consistent pattern: the app state and parameters are passed into a multimethod, which is
  dispatched on the symbol mentioned in the `transact!`. The basics are:

  - You key on a symbol instead of writing a function with that symbol name (this is what gives an abstraction that
  is already 'network ready')
  - You return a map
  - The `:action` key of that map specifies a function to run to accomplish the optimistic update to the browser database. The
  use of a thunk (function) here is what helps isolate asynchronous internals and networking from synchronous abstract reasoning.

  When you want to interact with a server, you need merely change it to:

  ```
  (defmethod m/mutate 'counter/inc [{:keys [state] :as env} k {:keys [id] :as params}]
    { :remote true ; <---- this is all!!!
      :action
        (fn []
          (swap! state update-in [:counter/by-id id] increment-counter))})
  ```

  and then write a similar function on the server that has an `:action` to affect the change on the server!

  At first this seems like a bit of overkill on the boilerplate until you realize that there are a number of things
  being handled here:

  - Sychronous *reasoning* about most aspects of the application.
  - Local optimistic (immediate) updates (through `:action`)
  - Automatically plumbed server interactions (triggered through `:remote` at transaction time)
  - Use of the same abstract symbol for the operation on the client and server.
  - Operations in the UI and on the server are identical in appearance.
  - Consistent implementation pattern for model manipulations on the client and server.
  - The operations 'on the wire' read like abstract function calls, and lead to easy auditing and reasoning, or
  even easy-to-implement CQRS architectures.

  ## An Example

  Let's write a simple application that shows counters that can be incremented. Each counter has an ID and a value (n).

  ### Making a Counter component

  Here's the Fulcro implementation of that UI component:

  "
  (dc/mkdn-pprint-source Counter)
  "

  (the `^:once` is helper metadata that ensures correct operation of development-time hot code reload)

  It looks a bit like a `defrecord` implementing protocols. Here is a description of the parts:

  - InitialAppState : Represents how to make an instance of the counter in the browser database. Parameters are supported
  for making more than one on the UI. This function should return a map that matches the properties asked for in the query.
  - IQuery : Represents what data is needed for the UI. In this case, a list of properties that exist (keys of the
  map that represents counters in the database). Note we can tune this up/down depending on the needs of the UI. Counters
  could have labels, owners, purposes, etc.
  - Ident : Represents the table/ID of the database data for the component. This is a function that, given an example
  of a counter, will return a vector that has two elements: the table and the ID of the entry. This is used
  to assist in auto-normalization of the browser database, and also with UI refreshes of components that display the
  same data.
  - Object/render : This is the (pure) function that outputs what a Counter should look like on the DOM

  The incoming data from the database comes in via `om/props`, and things like callbacks come in via a mechanism known
  as the 'computed' data (e.g. stuff that isn't in the database, but is generated by the UI, such as callbacks).

  A `defui` generates a React Component (class). In order to render an instance of a Counter, we must make an element
  factory:
  "
  (dc/mkdn-pprint-source ui-counter)
  "
  If more than one of something can appear in the UI as siblings, you must specify a `:keyfn` that helps React
  distinguish the DOM elements.

  The most important point is that you can reason about `Counter` in a totally local way.

  ### Combining Counters into a Panel

  Lets assume we want to display some arbitrary number of counters together in a panel. We can encode that (and the
  element factory) as:

  "
  (dc/mkdn-pprint-source CounterPanel)
  (dc/mkdn-pprint-source ui-counter-panel)
  "

  Note the mirroring again between the initial state and the query. The initial state provides an initial model
  of what should be in the database *for the component at hand*, and the query indicates which bits of that state are
  required for that same local component. (we're using `get-initial-state`. Technically you could call the protocol `initial-state`,
  but this helper function makes sure the composition can happen in server-side code as well for server-side rendering).

  In this case the query contains a map, which is the query syntax for a JOIN. You can read this query as
  'Query for :counters, which is a JOIN on Counter'. Note that since initial state is setting up a vector
  of counters, the implied result will be a to-many JOIN. The cardinality of JOINS is derived from the structure
  of the actual database, NOT the query.

  The join is necessary because this component will be rendering Counter components. The panel should not need to
  know anything concrete about the implementation of Counter: only that it has a query, initial state, and a way
  to render.

  The render is very similar to Counter's. The queried data will appear in props: pull it out, and
  then render it. Note the use of `ui-counter`, which is the DOM element factory for `Counter`.

  Finally, we include a callback. The counter has the 'Increment' button, but we've connected that from the
  counter UI back through to an implementation of a callback via 'computed' properties. This is a common pattern. In
  this simple example the callback structure was added to demonstrate what it looks like. One could argue that the
  counter could update itself (while maintaining local reasoning).

  The implementation of the `counter/inc` mutation was shown earlier.

  There are many important points to make here, but we would like to stress the fact that the UI has no idea
  *how* counters are implemented, *if* that logic includes server interactions, etc. There are no nested callbacks
  of callbacks, no XhrIO mixed into the UI, and interestingly: no need for controller at all!

  A simple pattern of updating an abstraction in a database is all you need.

  There is one exception: That weird inclusion of `:counter-sum` in the transaction. We'll explain that shortly.

  ### Adding the Panel to a Root element

  Fulcro UI (and DOM UI in general) forms a tree. All trees must eventually have a root.

  The UI composition (and the composition of queries and initial state) must continue to your UI Root. So, in our case
  our root looks like this:

  "
  (dc/mkdn-pprint-source Root)
  "

  The initial state is a composition from the nested panel, as is the query. We add in two additional things here:

  - `:ui/loading-data` is a special key, available at the root, that is true when data is loading over the network
  - We compose in another component we've yet to discuss (which sums all counters). We'll talk more about that shortly

  There should be no surprises here. It is the same pattern all over again, except Root does not need an `Ident`, since
  it need not live in a database table. The data for root can just be a standalone map entry (as can other things. There
  is *no rule that absolutely everything* lives in tables. Top-level entries for other data structures are also useful).

  ### Starting up the App

  Normally you create an Fulcro application and mount it on a DIV in your real HTML DOM. We'll cover that later in the
  Guide. Here we're in devcards and we have a helper to do that. It accepts the same basic parameters as
  `make-fulcro-app`, but mounts the resulting application in a devcard:

  ```
  (defcard SampleApp
     (fulcro-app Root
                    :started-callback (fn [app]
                                        (log/info \"Application (re)started\")
                                        (df/load app :all-counters Counter
                                          {:target [:panels/by-kw :counter :counters]}))))
  ```

  In this quick tour we're faking the networking so you can easily explore what the server and client look like in a
  single file.

  The `:started-callback` is triggered as the application loads. This is where you can use Fulcro's networking layer
  to pre-fetch data from the server. In our case, we want to get all of the counters. Note that we can (and should) use UI
  queries. This triggers auto-normalization of the response. The `:target` option indicates that the loaded data should
  be places in the database at the given path (usually 1 to 3 keywords in the vector, since the db is normalized). In
  this case the ident of the target panel gives the location of the component that wants the counters, and the `:counters`
  property of that component is where it wants to know about them. Therefore the desired target path is the ident + the property
  as a vector.

  You'll need to understand a bit more about the structure of the database to completely understand this code, but you
  see it is really quite simple: pull `:all-counters` from the server-side database, and put them on the panel according to the
  location (known via Ident) of the panel data in the database.

  ### Server Implementation

  We're representing our server database as an in-RAM atom:

  "
  (dc/mkdn-pprint-source server-state)
  "

  #### Server Queries

  To respond to the `:all-counters` query, we simply write a function that returns the correct data and hook it into
  the server engine. In real server code this is commonly done with a multimethod (your choice). The take-away here
  is that you write very little 'plumbing'...just the logic to get the data, and return it in a map with a
  `:value` key:

  "
  (dc/mkdn-pprint-source read-handler)
  "
  #### Server Mutations

  We mentioned earlier that turning a client mutation into something that also happens on the server is as simple as
  adding a `:remote true` to the response of the mutation function.

  The server code is equally trivial (again, in a real server you'd typically use a multimethod):
  "
  (dc/mkdn-pprint-source write-handler))

(defcard mock-server-state
  (dom/div nil "The state shown below is the active state of the mock server for the FinalResult card.")
  server-state
  {:watch-atom   true
   :inspect-data true})

(defcard FinalResult
  "Below is the final result of the above application, complete with faked server interactions (we use
  setTimeout to fake network latency). If you reload this page and jump to the bottom, you'll see the initial
  server loading. (If you see an error related to mounting a DOM node, try reloading the page). You can
  see the mocked server processing take place in a delayed fashion in the Javascript Console of your
  browser.

  NOTE: The map shown at the bottom is our simulated server state. Note how, if you click rapidly on
  increment, that the server state lags behind (because of our simulated delay). You can see how the UI
  remains responsive even though the server is lagging."
  (fulcro-app Root
    :started-callback (fn [app]
                        (log/info "Application (re)started")
                        (df/load app :all-counters Counter {:target [:panels/by-kw :counter :counters]}))
    :networking (map->MockNetwork {}))
  {}
  {:inspect-data true})

(defcard-doc
  "### The Grand Total

  You'll notice that there is a part of the UI that is tracking the grand total of the counters. This component shows
  a few more interesting features: Rendering derived values, querying entire tables, and refreshing derived UI.

  This is the implementation of the counter UI:
  "
  (dc/mkdn-pprint-source CounterSum)
  "
  The query is interesting. Queries are vectors of things you want. When one of those things is a two-element vector
  it knows you want an entry in a table (e.g. [:counter/by-id 3]). If you use the special symbol `'_`, then you're asking
  for an entire top-level entry (in this case a whole table).

  Thus we can easily use that to calculate our desired UI.

  However, it turns out that we have a problem: Nothing in Fulcro or Om Next will trigger this component to refresh
  when an individual counter changes! The mutations are abstract, and even though you can imagine that the UI refresh is
  a pure function that recreates the DOM, it is in fact optimized to only refresh the bits that have changed. Since the
  data that changed wasn't a counter, Om Next will short-circuit the sum for efficiency.

  This is the point of the earlier mysterious `:counter-sum` in the `transact!`. Any keyword that appears in a transaction
  will cause Om to re-render any component that queried for that property (in our case the Root UI component). These
  are called 'follow-on reads', to imply that 'anything that reads the given property should be asked to re-read it
  (and implicitly, this means it will re-render if the value has changed)'.

  You can, of course, play with the source code of this example in the devcards.

  We certainly hope you will continue reading into the details of all of these features. You should start with the
  [next chapter](#!/fulcro_devguide.B_UI) on UI.
  ")
