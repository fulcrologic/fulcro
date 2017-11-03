(ns fulcro-devguide.F-Fulcro-DevEnv
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(defcard-doc
  "
  # Recommended Development Environment and Setup

  This section covers some hard-won information about setting up your development environment for a good
  development experience.

  ## Recommended application layout for development

  A lot of thought has gone into how to lay out your application to be able to:

  - Run it in development mode with figwheel
  - Compile it to production code with advanced optimizations
  - Use the REPL support in your IDE/editor

  The primary components of this layout are:

  - A project file that is configured for running both the server, client, and tests with hot code reload.
  - An entry point for production that does nothing *but* mount the app. Nothing but the project build refers to this,
  so we don't accidentally try to do entry point operations more than once. It can also be used to do things
  like disable console logging.
  - A development-only namespace file that mounts the app in development mode. Only the dev build includes this source.
  - A core namespace that creates the application. This is referred to by the production
  build and the development-only namespace.

  In general, using the [Fulcro Template](https://github.com/fulcrologic/fulcro-template) as a starting point (clone it) is a good idea.

  ## Enabling re-render on hot code reload

  Om and React do their best to prevent unnecessary rendering. During development we'd like to
  force a refresh that whenever code reloads so we can see our changes.

  In order to do this:

  - Make sure all of your `defui` include metadata `^:once`.
  - Fulcro forces a full UI refresh if `mount` is called on an already mounted app. If you specify that the
  development namespace (which always mounts the app) is reloaded every time, then that is sufficient. If
  you have figwheel `:recompile-dependents true` in the project file  this typically
  ensures that the user development namespace is always reloaded. Since `mount` is called there, this is usually
  sufficient.
  - The application itself has a (protocol) method named `refresh`. You can configure
  figwheel to invoke a function that calls that after each load.
  - Include a changing react key on the root DOM node (React may otherwise fail to realize the DOM has changed because
  the underlying *data* did not change)

  This last item is supported automatically in Fulcro, but you have to cooperate by adding a react
  key from your app state onto your top-level DOM node, like this:

  ```
  (defui ^:once Root
     static prim/IQuery
     (query [this] [:ui/react-key ...])
     Object
     (render [this]
        (let [{:keys [ui/react-key ...]} (prim/props this)]
          (dom/div #js { :key react-key } ...))))
  ```

  The Fulcro refresh will automatically set this key in your app state to a new unique value
  every time, ensuring that the app state has changed. The changing data will cause Om to allow a re-render, and
  the top-level key change on the DOM will cause React to force a full DOM diff (ignoring the fact that the
  rest of the recursive state has not changed).

  ## Using the REPL

  ### Making sure you're connected to the right browser/tab

  If you're running more than one build in Figwheel the REPL will only be connected to one browser tab
  at a time. You can run `(fig-status)` to see what builds are running and how many browsers are
  connected to each.

  If you're trying to look at app state, make sure ONLY one browser is connected to it, otherwise
  you'll confuse yourself!

  To ensure that you're talking to the tab of the correct build, you should do the following at
  the REPL:

  ```
  cljs.user=> :cljs/quit
  Choose focus build for CLJS REPL (devguide, client, test) or quit > client
  Launching ClojureScript REPL for build: client
  ```

  ### Chrome dev tools

  ClojureScript has some Chrome dev tools that we highly recommend (and install in the project file by
  default):

  ```
  [binaryage/devtools \"0.9.2\"]
  ```

  They can be autoinstalled via a compiler preload. In your compiler config in `project.clj`, include
  `:preloads [devtools.preload]`

  AND you must enable custom formatters in Chrome dev tools: Dev tools -> Settings -> Console -> Enable Custom Formatters

  Once you've installed these you'll get features like:

  - Use `(js/console.log v)` and `v` will display as ClojureScript data
  - See cljs variables and other runtime information in the source debugger as ClojureScript data

  These tools are critical when trying to debug your application, as you can actually clearly see
  what is going on!

  ### Other Helper Functions

  The [template](https://github.com/fulcrologic/fulcro-template) includes a few
  [helper functions](https://github.com/fulcrologic/fulcro-template/blob/develop/dev/client/cljs/user.cljs#L23).
  for working with queries and the app database.

  #### Getting the query for some component (class)

  Sometimes it helps to see the expanded, composed query of a component:

  ```
  (dump-query Root)
  ```

  #### Getting the query for a component that mentions a specific keyword

  Sometimes it is hard to remember the component class name, but it is easy to remember one of the propery names
  it is querying:

  ```
  (dump-query :user/name)
  ```

  will find a component (if there is more than one, you'll get an arbitrary one) and show you the query on that component.

  #### Seeing the Data

  When debugging your app state it can be useful to see several things:

  - Seeing the entire app state:
    ```
    (log-app-state)
    ```
  - Show part of the app state (accepts single keywords or key-paths like `get-in`)
    ```
    (log-app-state [:users/by-id 4])
    ```
  - Run the Om Next query of a component against the app state, and show what a component is getting (NOTE: only
  works correctly if the target component is only in the UI tree once):
    ```
    (q Root)
    ```
  - Run the Om Next query of a component that asks for the given property. NOTE: only
  works correctly if there is a single component in the rendered UI (on screen) that is asking for that property.
    ```
    (qkw :user/name)
    ```

  ")
