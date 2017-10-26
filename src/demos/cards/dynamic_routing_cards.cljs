(ns cards.dynamic-routing-cards
  (:require [fulcro.client.dom :as dom]
            [recipes.dynamic-ui-routing :as dur]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.cards :refer [defcard-fulcro]]))

(defcard-doc
  "# Dynamic Routing

  The fulcro.client.routing namespace includes a second kind of router that can be used with the routing tree: DynamicRouter.

  A DynamicRouter uses a dynamic query to change routes instead of a union, and it can derive the details of the target
  component at runtime, meaning that it can be used to route to screens that were not in the loaded application code base
  at start-time.

  Furthermore, the routing tree has been designed to trigger the proper dynamic module loads for your dynamically loaded
  routes so that code splitting your application can be a fairly simple exercise. Here are the basic steps:

  1. Pick a keyword for the name of a given screen. Say `:main`
  2. Write the `defui` for that screen, and design it so that the TYPE (first element) of the ident is the keyword from (1).
      - The initial state must be defined, and it must have the name (1) under the key r/dynamic-route-key
      - The bottom of the file that defines the target screen *must* include a `defmethod` that associates the keyword (1) with the component (2). This
        is how the dynamic router finds the initial state of the screen, and the query to route to.
      - IMPORTANT: Your dynamically loaded screen *MUST* have a call to `(cljs.loader/set-loaded! KW)` at the bottom of the file (where KW is from (1)).
  3. Configure your cljs build to use modules. Place the screen from (2) into a module with the name from (1).
  4. Use a DynamicRouter for the router that will route to the screen (2). This means you won't have to explicitly refer to the class of the component.
  5. Create your routing tree as usual. Remember that a routing tree is just routing instructions (keywords).

  If you are routing through a DynamicRouter as part of your initial startup, then there are a few more steps. See Pre-loaded routes below.

  Trigger routing via the `route-to` mutation. That's it! The module rooted at the screen in (2) will be automatically loaded
  when needed.

  The defui and defmethod needed for step 2 look like this:

  ```
  (om/defui ^:once Main
    static fc/InitialAppState
    (initial-state [clz params] {r/dynamic-route-key :main :label \"MAIN\"})
    static om/Ident
    (ident [this props] [:main :singleton])
    static om/IQuery
    (query [this] [r/dynamic-route-key :label])
    Object
    (render [this]
      (let [{:keys [label]} (om/props this)]
        (dom/div #js {:style #js {:backgroundColor \"red\"}}
          label))))

  (defmethod r/get-dynamic-router-target :main [k] Main)
  (cljs.loader/set-loaded! :main)
  ```

  ## Pre-loaded Routes

  Screens used with DynamicRouter that are loaded at start-time are written identically to the dynamically loaded screen,
  but you will have to make sure their state and multimethod are set up at load time. This can be done
  via the mutation `r/install-route`. This mutation adds the screen's state *and* multimethod component dispatch.

  The demo application includes two such pre-installed route (`Login` and `NewUser`), and one dynamically loaded one (main).
  "
  (dc/mkdn-pprint-source dur/Login)
  (dc/mkdn-pprint-source dur/NewUser)
  (dc/mkdn-pprint-source dur/Root)
  "
  The code called at application startup to ensure the pre-loaded routes is:
  "
  (dc/mkdn-pprint-source dur/application-loaded))

(defcard-fulcro router-demo
  "Notice on initial load that the `[:main :singleton]` path in app state is not present. You could use the console to
  verify that `recipes.dynamic_ui_main.Main` is not present either. Once you route to Main, both will be present. You should
  see the network load of the code when you route as well. See the `demos` build configuration in project.clj as well.

  Note: The build config with devcards requires us to name more namespaces than would normally be required because
  devcards does a dynamic load of the cards that prevents `cljs.loader` from accurately understanding code relationships.
  "
  dur/Root
  {}
  {:inspect-data true
   :fulcro       {:started-callback dur/application-loaded}})
