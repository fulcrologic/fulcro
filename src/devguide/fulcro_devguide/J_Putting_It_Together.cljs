(ns fulcro-devguide.J-Putting-It-Together
  (:require-macros [cljs.test :refer [is]]
                   [fulcro-devguide.tutmacros :refer [fulcro-app]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [fulcro-devguide.putting-together.soln-ex-1 :as soln1]
            [fulcro-devguide.putting-together.soln-ex-2 :as soln2]
            [fulcro-devguide.putting-together.soln-ex-3 :as soln3]
            [fulcro-devguide.putting-together.soln-ex-4 :as soln4]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.core :as fc]
            [fulcro.client.data-fetch :as df]))

(defcard-doc
  "
  # Putting it all together

  This section is a large interactive exercise. In this exercise you'll use the server that is partially built in
  this project, and you'll build your client right here in this file (in devcards).

  Our goal is to demonstrate the following core features:

  - Building a UI that will be (eventually) populated from a server (using InitialAppState as a guiding light)
  - Writing a server-side query response that can fill that UI
  - Using the `started-callback` to trigger an initial load of the data into the UI
  - Working with mutations that do optimistic update and remote mutations

  ## Setting up

  First, review the steps for getting the server running in [Building A Server Exercises](#!/fulcro_devguide.I_Building_A_Server_Exercises).

  Make sure you can start the server, and make sure you're loading this *through the server* (e.g. check
  the port, it should not be 3449. Check your server logs, as the port is reported there.)
  ")

(defui CheckSetupRoot
  static fc/InitialAppState
  (initial-state [this params] {})
  static om/IQuery
  (query [this] [:ui/react-key :something])
  Object
  (render [this]
    (let [{:keys [ui/react-key something]} (om/props this)]
      (dom/div #js {:key react-key :style #js {:border "1px solid black"}}
        (if (= 66 something)
          (dom/p nil "SERVER RESPONDED WITH 66!")
          (dom/p nil "No response from server. You might have a problem. Make sure the API in api.clj is returning {:value 66} for :something, and your browser is hitting the correct server port."))))))

(defcard check-setup
  "This card checks to see if your server is working. Start your server, then reload this page on that server and you should see a SERVER RESPONDED message."
  (fulcro-app CheckSetupRoot :started-callback (fn [{:keys [reconciler] :as app}] (df/load reconciler :something nil))))

(defcard-doc "
  ## The Project

  We're going to write a simple TODO list, with a very simple UI. Use what you've learned to lay out the following
  DOM (via defui):

  ```html
  <div>  <!-- TodoList -->
    <div>  <!-- ItemList -->
      <h4>TODO</h4>
      <input type=text><button>Add</button>
      <ol>
        <li><input type=checkbox> Item 1 <button>X</button></li> <!-- TodoItem -->
        <li><input type=checkbox> Item 2 <button>X</button></li> <!-- TodoItem -->
        <li><input type=checkbox> Item 3 <button>X</button></li> <!-- TodoItem -->
      </ol>
    </div>
  </div>
  ```

  ## Exercise 1 - Create the UI

  You should have components for `TodoList` (which will be your root), `ItemList`, and `TodoItem`.
  `K_Putting_It_Together.cljs` has the basic bits already named.

  Remember:
  - Use InitialAppState, and compose a few manually created items into the list to verify rendering
  - include `Ident` on `TodoItem` and `ItemList`
  - include a query everything (list title, items, item details (label, done, id)
  - don't foget static
  - A :keyfn on the TodoItem factory

  When you're done, the devcard should render things correctly. There are solutions in `fulcro_devguide/putting_together/soln_ex_1.cljs`
  if you get stuck.
  ")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;START HERE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
#_(defui ^:once TodoItem
    Object
    (render [this]))

#_(defui ^:once ItemList
    Object
    (render [this]))

(defui ^:once TodoList
  Object
  (render [this] (dom/div nil "TODO")))

(defcard todo-list-application
  "This card can be used to show your application. "
  (fulcro-app TodoList)
  {}
  {:inspect-data true})

(defcard-doc "
  ## Exercise 2 -- Add Some Local Mutations

  First, let's make it so we can check/uncheck items. A simple mutation that toggles done should do this, once it is
  hooked to `:onChange` of the input.

  Once you've done that, hook up the input field and button to add items. The mutation should add a new item to the database
  of items and append it (use `integrate-ident!`) to the list of items. Remember to use `om/tempid` (as a
  parameter of the UI transaction) to generate an ID for the new item.

  Next, hook up the delete button to remove items.

  The solutions to this exercise are in `putting_together/soln_ex_2.cljs`.

  ## Exercise 3 -- Add Server Support

  First, remove all of your pregenerated todo items from your InitialAppState.

  Now that your application is working on the client, let's add in server support!

  Create a new server namespace and make an in-memory database:

  ```clojure
  (def lists (atom { ...db of lists/items... }))
  (def next-id (atom 0))
  ```

  Now add implementations of the multimethods that can support your UI operations on the server by manipulating this
  in-memory database. Feel free to structure it however you want.

  It will be useful if you log your database as server interactions occur so you can see things working. Be sure to
  reset your server to refresh code!

  The solutions for this exercise are in
  `src/devguide/fulcro_devguide/putting_together/soln_ex_3.cljs` and
  `src/server/solutions/putting_together.clj`.

  ## Exercise 4 -- Initial Load

  You should have a mostly-working application at this point, except for one thing: if you
  reload the UI, the UI does *not* reflect the state of the server! The UI need not change
  for this, but you'll need to add a `started-callback` to the app, make a query that
  hits the server, and a post-mutation that can properly transform the fetched data
  into the app state.

  **Bonus**: The post mutation can be totally avoided by generating a query targeted correctly.

  A solution can be see by uncommenting the devcard at the bottom of this source page.

  ## Further Reading

  There are many examples of client-only and full-stack applications in the
  [Fulcro Demos](https://github.com/fulcrlogic/fulcro/src/demos).

  ## A Final, working, solution
  ")


(defcard todo-list-application-solution-post-mutation
  "A final solution. The source is in:

  - `fulcro-devguide.putting-together.soln-ex-3` (cljs for UI)
  - `fulcro-devguide.putting-together.soln-ex-4` (cljs for post mutation)
  - `solutions.putting-together` (clj server-side API implementation)

  NOTE: THESE TWO SOLUTIONS SHARE server state but are not connected. Thus, changing one will not reflect in the other.
  You should reload the page between playing with each of them.
  "

  (fulcro-app soln3/TodoList
    :started-callback
    (fn [{:keys [reconciler]}]
      (df/load reconciler :ex4/list soln3/TodoItem {:params        {:list "My List"}
                                                    :refresh       [:item-list]
                                                    :post-mutation 'pit-soln/populate-list})))
  {}
  {:inspect-data true})

(defcard todo-list-application-solution-bonus
  "The bonus for the final solution:

  You can avoid the post-mutation by loading via ident. Of course you must implement
  the query response correctly on the server, which we've done in `putting_together.clj`):

  The load will be:

  ```clojure
  (df/load reconciler [:lists/by-title \"My List\"] soln3/ItemList)
  ```

  and the query sent to the server will end up being a join on the ident:

  ```clojure
  [{[:lists/by-title \"My List\"] (om/get-query ItemList)}]
  ```

  NOTE: THESE TWO SOLUTIONS SHARE server state but are not connected. Thus, changing one will not reflect in the other.
  You should reload the page between playing with each of them.
  "
  (fulcro-app soln3/TodoList
    :started-callback
    (fn [{:keys [reconciler]}]
      (df/load reconciler [:lists/by-title "My List"] soln3/ItemList)))
  {}
  {:inspect-data true})

