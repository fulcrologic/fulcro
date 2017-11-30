(ns fulcro-devguide.J-Exercise-on-Putting-It-Together
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [solutions.putting-together.soln-ex-1 :as soln1]
            [solutions.putting-together.soln-ex-2 :as soln2]
            [solutions.putting-together.soln-ex-3 :as soln3]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [devcards.core :as dc :refer-macros [defcard-doc]]
            [fulcro.client.core :as fc]
            [fulcro.client.data-fetch :as df]))

(defcard-doc
  "
  # Putting it all together

  This section is a large interactive exercise in building a small full-stack application. The assumption is that
  you've cloned the fulcro project and are running the devguide locally. This would mean, for example, that you're
  probably currently running a figwheel build of the devguide.

  In this exercise you'll create a server that can serve not only the devguide files, but process requests from a card
  running a fulcro application on this page.

  Our goal is to demonstrate the following core features:

  - Building a UI that will be populated from a server (using pretend data in InitialAppState as a way to direct initial efforts)
  - Writing a server-side query response that can fill load requests.
  - Using the `started-callback` to trigger an initial load of the data into the UI.
  - Working with mutations that do optimistic update and remote mutations.

  ## Setting up

  You should start a regular Clojure REPL. This should put you in the `user` namespace (this is in `src/dev/user.clj`).
  The user namespace contains a number of things for working with this project, including the code to start figwheel,
  and a couple of other demo servers.

  We will be adding our own server startup and restart code here, as you would in any project where you'd like to do
  reasonably rapid development of server code.

  Do the following steps to make your server (solutions are already in the respecting namespaces in comment blocks).

  Edit `src/devguide/solutions/putting_together.clj` and:

  1. Create an atom to hold your running server. It should start with a nil value.
  2. Add a exercise.edn file in src/devguide/config. This will be your configuration. The content should be `{:port 9000}`.
  3. Add a function that will use the fulcro.easy-server API to make a server. Indicate that the config is `config/exercise.edn` (a relative path will cause it to look on classpath for resources).

  In the `user.clj` namespace, add a `ex-start`, `ex-stop`, and `ex-restart` function. Mimic the ones that are already there,
  but use your server definition from above. Start a Clojure REPL and try them out.

  You should now be able to start/stop/restart your server with code refresh. Try editing the server.clj file and restarting. You should see
  a message about the namespace being reloaded.

  **NOTE: If you have a compile error during restart then you may have to run `(tools-ns/refresh)` in order to get things working again. You should
  not have to restart the REPL.**

  When that is working, you should be able to change the port of the URL of this page to 9000. At that point you are ready
  to continue.

  ## Trying a Simple Query

  The card below will start an app that queries the server for the root keyword `:something`. Add a query handler to
  your server that returns the value `66`. Clicking on the `load` button in the card will trigger trial loads, and
  the UI will show the result.

  ")

(defui ^:once CheckSetupRoot
  static prim/InitialAppState
  (initial-state [this params] {})
  static prim/IQuery
  (query [this] [:ui/react-key :something])
  Object
  (render [this]
    (let [{:keys [ui/react-key something]} (prim/props this)]
      (dom/div #js {:key react-key :style #js {:border "1px solid black"}}
        (dom/button #js {:onClick #(df/load this :something nil)} "Load")
        (if (pos? something)
          (dom/p nil (str "OK! SERVER RESPONDED WITH " something))
          (dom/p nil "No response from server. Are you on port 9000? Is the server running? Have you pressed Load?"))))))

(defcard-fulcro check-setup CheckSetupRoot)

(defcard-doc "
  ## The Project

  We're going to write a simple TODO list, with a very simple UI. Use what you've learned to lay out the following
  DOM (via defui). The HTML comments indicate the use of a `defui` to make a component:

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
  This file has the basic bits already named, so if you edit it and search for them you'll find
  the basic bits with comments.

  Specifically:
  - Use InitialAppState, and compose a few **manually** created items into the list to verify rendering is OK.
  - Include `Ident` on `TodoItem` and `ItemList` so they are normalized.
  - Include a query for everything (list title, items, item details (label, done, id)
  - Don't foget static on the protocols.
  - Include a :keyfn on the TodoItem factory so items will render properly in React.

  When you're done, the todo-list-application devcard should render things correctly. There is a proposed solutions for
  this in `devguide/solutions/soln_ex_1.cljs` if you get stuck.
  ")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; EX1-3: Code a todo list
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

(defcard-fulcro todo-list-application
  "This card can be used to show your application.

  NOTE: YOU MUST RELOAD THE PAGE TO TRY IT (initial state is only read on the first start)!"
  TodoList
  {}
  {:inspect-data true
   ;; SOLUTION TO CLIENT-SIDE for EXERCISE 4
   ;:fulcro       {:started-callback
   ;               (fn [app]
   ;                 (df/load app :ex4/all-items soln3/TodoItem {:target [:lists/by-title "My List" :list/items]})
   ;                 )}
   })

(defcard-doc "
  ## Exercise 2 -- Add Some Local Mutations

  Next, let's make it so we can check/uncheck items. A simple mutation that toggles `:complete` should do this, once it is
  hooked to `:onChange` of the input.

  Once you've done that, hook up the input field and button to add items. The mutation should add a new item to the database
  of items and append it (use `integrate-ident!`) to the list of items. Remember to use `prim/tempid` (as a
  parameter of the UI transaction) to generate an ID for the new item.

  Next, hook up the delete button to remove items.

  The solutions to this exercise are in `putting_together/soln_ex_2.cljs`. Your trial devcard above should
  now allow both operations.

  ## Exercise 3 -- Add Server Support

  First, remove all of your pre-generated todo items from your InitialAppState.

  Now that your application is working on the client, let's instead add in server support!

  Edit `src/devguide/solutions/putting_together.clj` and make an in-memory database. Something like:

  ```clojure
  ; a map keyed by item ID is suggested. You can sort by ID to get the order right since the items are assigned IDs in asc order
  (def items (atom {}))
  (def next-id (atom 0))
  (defn get-next-id [] (swap! next-id inc))

  ...
  ```

  Now add support functions and server-side mutation implementations that can support your UI.
  Be sure to reset your server to refresh code!

  IMPORTANT: defmutation puts the mutations in the current namespace by default. If you code those in this file, then
  your server mutations won't match in namespace and will not be found. You can override this behavior by
  explicitly giving your symbols a namespace (on the client or server or both):

  ```
  (defmutation soln/add-item ...)
  ```

  EXTRA CREDIT: For the toggle, you might try a little defensive coding. If more than one user were using the list, your server might
  be out of sync with the UI. Therefore, toggle itself might do the wrong thing. There are several ways to solve this,
  but for this exercise use the AST in the mutation on the client in order to pass the *desired* new state with the
  mutation, so the server knows what to toggle to.

  ## Exercise 4 -- Initial Load

  You should have a mostly-working application at this point, except for one thing: if you
  reload the UI, the UI does *not* reflect the state of the server! The UI need not change
  for this, but you'll need to add a `started-callback` to the app and make a query that
  pulls the items from the server, and target it to the correct location in your client state.

  Since this requires a `started-callback`, you'll need to modify the devcard options:

  ```
  (defcard-fulcro todo-list-application
    TodoList
    {}
    {:inspect-data true
     :fulcro {:started-callback (fn [app] ...) })
  ```

  and reload the page to trigger it. Now reloading the page should put the list back the way you had it.

  Remember to refresh your server and reload the page to see your changes.

  HINT: Your query function on the server is going to need to return a vector. Be careful that you do not return a plain list or a
  map.

  ## Further Reading

  There are many examples of client-only and full-stack applications in the
  [Fulcro Demos](https://github.com/fulcrologic/fulcro/tree/develop/src/demos).
  ")

