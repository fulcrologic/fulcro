(ns fulcro-tutorial.F-Fulcro-Initial-App-State
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.client :as fc]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [cljs.reader :as r]
            [fulcro.client.mutations :as m]))

(defsc Child [this {:keys [x]}]
  {:initial-state (fn [params] {:id 1 :x 1})
   :query         [:id :x]
   :ident         [:child/by-id :id]}
  (dom/p nil (str "Child x: " x)))

(def ui-child (prim/factory Child))

(defsc Root [this {:keys [ui/react-key root-prop child]}]
  {:query         [:ui/react-key :root-prop {:child (prim/get-query Child)}]
   :initial-state (fn [params] {:root-prop 42 :child (prim/get-initial-state Child {})})}
  (dom/div #js {:style #js {:border "1px solid black"} :key react-key}
    (str "Root prop: " root-prop)
    (ui-child child)))

(defcard-doc
  "
  # Initial Application State

  Most applications need some kind of state to represent the starting conditions of an application. You've already
  studied the format of this (the application graph database), you have also worked with queries and have seen
  how the normalized graph can be turned into a tree. We've also discussed co-locating an ident generating function
  to enable auto-normalization, and now it's time to see it in action!

  The mechanism uses the ui query and ident functions to walk the tree of data and normalize it. Fulcro leverages this
  for initial state, and will autodetect a tree of initial data automatically if you simply co-locate it (statically) on your
  components using the InitialAppState protocol.

  You simply implement this protocol in your component tree (for the components that will initially show) and compose
  it in parallel with your application query and UI tree. This helps quite a bit with local reasoning and makes
  it much easier to find/fix problems with your application's startup. It also makes it trivial when you refactor your
  UI and move things around: the normalized graph self-heals!

  ## The Basics

  There is a built-in normalization mechanism called `prim/tree->db`. It takes a UI query and some data and turns it
  into a graph db. This works because when you call `get-query` against a component the component itself is annotated
  onto the query as metadata. This allows the normalization to look up the correct `ident` function as it walks the
  query. It is also why a plain hand-written query won't work.

  ```
  (def q (prim/get-query Root))
  (-> q meta :component)
  ;; => Root
  ```

  If you manually traverse the query `q` you'll find that each node of the query that came from a distinct component
  has this `:component` metadata entry. This is the magic behind the curtains that allows auto-normalization to work,
  and is also one of the reasons you call `(get-query Root)` instead of the method *you* defined called `query`.

  ## Adding Initial Application State

  If your root component has initial state then Fulcro will detect that and use the query to
  auto-normalize it using `tree->db`. This will then become your initial normalized graph database for your application.

  The steps are as follows:

  1. Add `:initial-state` to options (or in `defui` implement the `InitialAppState` protocol)
     - Return a map of data that matches the query of the component
     - Make sure all of the joins in the query are also represented by composed initial state from the joined component(s)
  2. Repeat for all children that need to be part of your *initial* application state.

  ```
  (ns ...
     (:require [fulcro.client :as fc]))
  ```

  "
  (dc/mkdn-pprint-source Child)
  (dc/mkdn-pprint-source ui-child)
  (dc/mkdn-pprint-source Root)

  "
  Note the parallel composition of queries and state. It really is that simple: just use the `prim/get-initial-state` function
  to grab the child state and make sure each component that should appear has some initial state that matches what is
  asked for in the query. Be careful that
  any component with an ident also has the data in its initial state needed by that `ident` function, or things won't normalize
  correctly!

  A `defui` NOTE: If you use `defui` instead of `defsc` beware that you want `prim/get-initial-state` instead of calling the protocol
  `initial-state` directly. The latter will work in cljs, but if you ever want to use your components with server-side rendering
  then you'll have a problem. This is because the JVM doesn't support any way of doing `Class<Component> implements
  InitialAppState` (which is what the `static` qualifier implies). So, for server-sider rendering we have to do a
  bit of behind-the-scenes magic that we've wrapped in `get-initial-state`.

  Your top-level creation of the client now becomes *much* simpler, because you no longer have to worry about some external
  data structure matching your UI! Fulcro will sense initial state (if it exists in the
  root component), and will automatically:

  1. Pull the initial state and use that as base app state
  2. Ensure that it is normalized
  3. Will do a post-initialization step to ensure *alternate branches* of to-one unions are initialized.
  ")

(defcard-fulcro sample-app
  "This card shows the app from above, actively rendered. The resulting normalized app database is shown below the UI."
  Root
  {}
  {:inspect-data true})

(defcard-doc ["
  ## Error Checking Initial App State

  The `defsc` macro supports the template and lambda form for initial state, much like it does for query and ident; however,
  since initial state can take parameters it is a bit more complicated and the support is considered experimental.

  We generally recommend using the lambda form for most initialization, though if you need just a simple map of simple values,
  know that you can specify initial state as:

  ```
  (defsc Component [this props]
   {:initial-state {:id :mine
                    :x 1}
    :query [:id :x]
    :ident [:component/by-id :id]}
    ...)
  ```

  You can obtain parameters that were given when another component asks for initial state using `:param/name` keywords
  as data placeholders (EXPERIMENTAL):

  ```
  (defsc Component [this props]
   {:initial-state {:id :mine
                    :x :param/x}
    :query [:id :x]
    :ident [:component/by-id :id]}
    ...)

  ...

  (get-initial-state Component {:x 3}) ; => {:id :min :x 3}
  ```

  If the query joins in a component, then the template form requires that you just specify the desired parameters
  (not call `get-initial-state` yourself). This is intended to eliminate boilerplate, but is again an experimental
  feature:

  ```
  (defsc Component [this props]
   {:query [{:child (prim/get-query Child)}]
    :initial-state {:child {}} ; means {:child (prim/get-initial-state Child {})}
    ...)
  ```

  This feature supports to-one and to-many initialization:

  ```
  (defsc Component [this props]
   {:query [{:children (prim/get-query Child)}]
    :initial-state {:children [{:a 1} {:a 2}]} ; means {:children [(prim/get-initial-state Child {:a 1}) (prim/get-initial-state Child {:a 1})]}
    ...)
  ```

  Parameter keywords can be nested anywhere in the template form, but realize that they are always evaluated in the context of the
  initial state they are in (not passed to children as param keywords).

  ## Union Initialization

  The last step mentioned in the prior section is particularly handy, especially if you use Unions for UI routing and need all of the
  alternates to be in your initial state! The *data* tree branch at a to-one union can only have one target...it is to-one!
  But, you might need the state for all of the *possible* branches (everything listed in your union query).

  So, a to-one union names multiple branches of the *query*, but there is only one place
  in app state to store the reality of *now* (the initial state). For example, if you had a union query to switch between the `Main`
  and `Settings` tab:

  ```
  ; suggested query on Root component:
  [{:tab-switcher (prim/get-query TabSwitcher)}]
  ; which expands to:
  [{:tab-switcher { :main (prim/get-query Main) :settings (prim/get-query Settings) }}]
  ```

  and the `ident` function generates idents `[:main-tab :id]` and `[:settings-tab :id]` for the two possible tabs. What
  you would *like* to see in initial app state is something like this:

  ```
  { :tab-switcher [:main-tab :id]
    :main-tab { :id { :main-tab-property 1 } }
    :settings-tab { :id { :settings-tab-property 2}}}
  ```

  However, if you view this as a tree from `:tab-switcher` it is plain to see that there is no way to have *both* settings
  and main in place at the same time! Thus, when you go to compose the initial state in the UI you have something like this:

  ```
  ; Assumed composed from an initial state in root of `{:tab-switcher (get-initial-state TabSwitcher)}`
  ; which expands to `{:tab-switcher (get-initial-state MainTab)}`
  ; which give the final initial 'tree' of:
  { :tab-switcher { :main-tab-property 1 } }
  ```

  which normalizes properly but does not include the settings stuff in application state.

  So, after Fulcro has set the initial state to your composed initial tree it:

  - Scans the full application query for Unions
     - If (and only if) the union is a to-one (it detects a single map as the initial state of the union component instead of a vector)
        - It checks each *component* (via metadata) of the union *query* for initial state
        - For each component that implements `InitialAppState`, it normalizes and merges that component state into the database

  This ensures that all branches of the to-one union can be initialized in app state.

  The to-many case works without any post-step since you can put the *actual* initial state of multiple things into the
  resulting initial vector of tree state.

  ## Additional Helpers

  If there is some portion of app state that you'd like to manipulate outside of the provided mechanism, then you can of
  course use the `started-callback` of fulcro client. The callback will run *after* initial app state is complete.

  There are two useful methods that can help you manipulate application state:

  ```
  (prim/merge-component! [app component-class data & ident-merges])
  (prim/integrate-ident! [state-atom ident & ident-merges])
  ```

  where `ident-merges` are pairs (named parameters) that indicate where to place the ident of the resulting data.

  The `merge-component!` function uses the query and ident of a component to take a single (possibly recursive) component's
  raw data and merge it with the app database. This is exactly what is used in the post-step to merge singleton union
  elements. It is also useful for things like merging server push data or arbitrary AJAX results (e.g. autocomplete)
  at arbitrary times.

  The supported `ident-merges` are:

  - `:append ks` : Append the ident of the merged data onto the vector at path `ks`
  - `:replace ks` : Replace the ident of the merged data at path `ks` (which can be a singleton, or position in vector)
  - `:prepend ks` : Prepend the ident of the merged data onto the vector at path `ks`

  These helpers are descibed in more detail in the doc strings and in [Advanced Server Topics](#!/fulcro_tutorial.M40_Advanced_Server_Topics).

"])


(defsc AQueryRoot [this {:keys [root-prop]}]
  {:initial-state (fn [p] {})                               ; empty, but present initial state
   :query         (fn [] [[:root-prop '_]])}                ; A asks for something from root, but has no local props (empty map)
  (dom/p nil "A got " (if root-prop root-prop "Nothing!")))

(def ui-a (prim/factory AQueryRoot))

(defsc BQueryRoot [this {:keys [root-prop]}]
  ; no initial state
  {:query (fn [] [[:root-prop '_]])}                        ; B asks for something from root, no local props (nil for state)
  (dom/p nil "B got " (if root-prop root-prop "Nothing!")))

(def ui-b (prim/factory BQueryRoot))

(defsc RootQueryRoot [this {:keys [a b]}]
  {:initial-state (fn [p] {:root-prop 42
                           :a         (prim/get-initial-state AQueryRoot {})}) ; b has no state
   :query         [{:a (prim/get-query AQueryRoot)}
                   {:b (prim/get-query BQueryRoot)}]}
  (dom/div nil
    (ui-a a)
    (ui-b b)))

(defcard-doc "

  ## Comments on Initial State

  The benefits of this mechanism are huge:

  - Component-local reasoning about your startup data state. Your initial state is co-located with the component.
  - Refactoring is trivial! Your initial state automatically reshapes itself when you move/re-compose a UI component.
  - Components are fully embeddable in a devcard for component-centric development. Just write a devcard root and compose initial state! Even
  server interactions are just normalized into tables, so those can be easily used in smaller contexts as well.

  The costs are very low. In fact, the costs are the minimum amount of work and complexity you could possibly have:
  state what is needed at UI startup in the context of the thing that needs it.

  Please do not confuse this mechanism with a constructor. It is not really that. It is simply a mechanism whereby you
  can co-locate the data that will **fulfill** your initial application's query **for a given query fragment**. You can
  technically use a component's initial state to build new instances during operation, but the primary purpose is
  getting the startup state of your application built.

  ### Root Link Queries

  In the D_Queries section we discussed link queries. These are the queries that pull data from the root of the
  graph (e.g. `[ [:root-prop '_] ]`). They work perfectly with all of this; However, there is a small gotcha.

  IF you have a component that queries for **nothing except through a link query**, then that component **must** have
  at least an empty map as it's local state. This is due to the fact that the query engine walks the query in parallel
  with the app state. If it sees no entry in the app state for the entire component, then it won't bother to work on it's
  query (philosophically, this is a questionable case anyhow...why is it a stateful component if it has no state?).
  Here's an example that shows two components querying for a root prop, but only one has an (empty, but
  extant) initial state."
  (dc/mkdn-pprint-source AQueryRoot)
  (dc/mkdn-pprint-source BQueryRoot)
  (dc/mkdn-pprint-source RootQueryRoot))

(defcard-fulcro root-prop-query-gotcha
  "The live version of the code showing a root prop query problem. B has no state at all, so it's query isn't processed
  even though we could technically find the data needed. Queries and state are walked together by the query processing,
  so missing state for a component will keep its query from being processed.

  If you see data for b, it means we've fixed this, and the docs are out of date :)"
  RootQueryRoot {} {:inspect-data true})

(defcard-doc "
  ## Moving on

  Now that you've got a much easier way to create your initial state, you're probably interested in seeing your
  application do more than render static state. It's time to learn about [mutations](#!/fulcro_tutorial.G_Mutation)!

  At some point you'll also be interested in setting up [a development environment](#!/fulcro_tutorial.F_Fulcro_DevEnv)
  for your own project.
  ")


