(ns fulcro-tutorial.B-UI
  (:require-macros
    [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            cljsjs.d3
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(defsc Widget [this props]
  (dom/div nil "Hello world"))

(defsc WidgetWithHook [this props]
  {:componentWillUpdate (fn [nextprops nextstate] (println "Component will update"))}
  (dom/div nil "Hello world"))

(def ui-widget (prim/factory Widget))

(defsc WidgetWithProperties [this {:keys [name]}]
  (dom/div nil (str "Hello " name)))

(def ui-prop-widget (prim/factory WidgetWithProperties))

(defcard-doc
  "
  # UI

  NOTE - Namespace aliases used in this document:

```clojure
(require '[fulcro.client.primitives :as prim :refer [defsc]]
         '[fulcro.client.dom :as dom])
```

  Fulcro uses <a href=\"https://facebook.github.io/react/index.html\" target=\"_blank\">React</a> underneath.
  The primary mechanism for creating components is the `defsc` macro:"
  (dc/mkdn-pprint-source Widget)
  "This macro generates a React class as a plain JavaScript class, so it is completely compatible with the
  React ecosystem.

  NOTE - When implementing the body the last top level element will be returned.

  ```clojure
  (defsc Widget [this props]
    (dom/div ...)  ;; <- This will not be returned.
    (dom/div ...)) ;; <- This will.
  ```

  The render body should be a pure function whenever possible (avoiding component
  local state). Pure rendering is one of the secrets to simplicity.
  Making your rendering pure means that if you ever feel the need to write
  tests around how the UI works (say, for acceptance testing) then you can do so very easily. The lack
  of local state means that the code tends to be so simple as to avoid most of the bugs that plague other
  frameworks.

  ## React lifecycle methods

  If you wish to provide <a href=\"https://facebook.github.io/react/docs/component-specs.html#lifecycle-methods\"
  target=\"_blank\">lifecycle methods</a>, you can include those in an options map that immediately follows the argument
  list:
  "
  (dc/mkdn-pprint-source WidgetWithHook)
  "

  The complete list of method signatures can be found in the [cheat sheet](https://github.com/fulcrologic/fulcro/blob/develop/docs/CheatSheet.adoc).

  ## Element factory

  In order to render components on the screen you need an element factory.
  You generate a factory with `prim/factory`, which will then
  act like a new 'tag' for your DOM (we prefer to prefix our factories with `ui-` to avoid name collisions):"

  (dc/mkdn-pprint-source ui-widget)

  "Since they are plain React components you can render them in a <a href=\"https://github.com/bhauman/devcards#devcards\"
  target=\"_blank\">devcard</a>, which makes fine tuning them as pure UI dead simple:

  ```
  (defcard widget-card (widget {}))
  ```

  The resulting card looks like this:")

(defcard widget-card (ui-widget {}))

(defcard-doc
  "Components can be passed props, which appear as props in their arguments. Later,
  when we learn about colocated queries you'll see it is possible for a component to ask for the data it needs in
  a declarative fashion.

  For now, understand that you *can* give data to a stateless component via a simple [EDN](https://github.com/edn-format/edn) map, and pull it out
  with destructuring on `defsc`'s second argument:"
  (dc/mkdn-pprint-source WidgetWithProperties)
  (dc/mkdn-pprint-source ui-prop-widget)
  "

  Again, you can drop this into a devcard and see it in action:
  ```
  (defcard props-card (prop-widget {:name \"Sam\"}))
  ```
  ")

(defcard props-card (ui-prop-widget {:name "Sam"}))

(defsc Person [this {:keys [name]}]
  (dom/li nil name))

(def ui-person (prim/factory Person {:keyfn :name}))

(defsc PeopleList [this people]
  (dom/ul nil (map ui-person people)))

(def ui-people-list (prim/factory PeopleList))

(defsc Root [this {:keys [people number]}]
  (dom/div nil
    (dom/span nil (str "My lucky number is " number " and I have the following friends:"))
    (ui-people-list people)))

(def ui-root (prim/factory Root))

(defcard-doc
  "
  ## Composing the UI

  Composing these is pretty straightforward: pull out the bits from props, and pass them on to subcomponents.
  "
  (dc/mkdn-pprint-source Person)
  (dc/mkdn-pprint-source PeopleList)
  (dc/mkdn-pprint-source Root)
  (dc/mkdn-pprint-source ui-people-list)
  (dc/mkdn-pprint-source ui-person)
  (dc/mkdn-pprint-source ui-root)
  "

  Dropping this into a devcard with a tree of data will render what you would expect:

  ```
  (defcard root-render (root {:number 52 :people [{:name \"Sam\"} {:name \"Joe\"}]}))
  ```

  It is important to note that _this is exactly how the composition of UI components always happens_, independent of
  whether or not you use the rest of the features of Fulcro. A root component calls the factory functions of subcomponents
  with an EDN map as the first argument. That map is accessed using `props` within the subcomponent. Data
  is passed from component to component through `props`. All data originates from the root.

  ### Don't forget the React DOM key!

  You might notice something new here: the `prim/factory` function is supplied with an additional map `{:keyfn :name}`.
  The factory function can be optionally supplied with `:keyfn` that is used to produces the
  <a href=\"https://facebook.github.io/react/docs/multiple-components.html\" target=\"_blank\">React key property</a>
  from component props (here it's using `:name` as a function, which means \"look up :name in props\").

  The key is critical, as it helps React figure out the DOM diff. If you see warning about elements missing
  keys (or having the same key) it means you have adjacent elements in the DOM of the same type (technically in a collection),
  and React cannot figure out what to do to make sure they are properly updated.

  ## Play with it

  At this point (if you have not already) you should play with the code in `B-UI.cljs`. Search for `root-render`
  and then scan backwards to the source. You should try adding an object to the properties (another person),
  and also try playing with editing/adding to the DOM.
  ")

(defcard root-render (ui-root {:number 52 :people [{:name "Sam"} {:name "Joe"}]}))

(defsc Root-computed [this
                      {:keys [people number b]}
                      {:keys [incHandler boolHandler]}]
  (dom/div nil
    (dom/button #js {:onClick #(boolHandler)} "Toggle Luck")
    (dom/button #js {:onClick #(incHandler)} "Increment Number")
    (dom/span nil (str "My " (if b "" "un") "lucky number is " number
                    " and I have the following friends:"))
    (ui-people-list people)))

(def ui-root-computed (prim/factory Root-computed))

(defcard-doc
  "
  ## Out-of-band data: Callbacks and such

  In plain React, you (optionally) store component-local state and pass data from the parent to the child through props.
  Fulcro is no different, though component-local state is discouraged for everything except high-performance transient
  things like tracking animations.

  In React, you pass your callbacks through props. In Fulcro, we need a slight variation of this.

  In Fulcro, a component can have a [query](/index.html#!/fulcro_tutorial.D_Queries) that asks
  the underlying system for data. If you mix callbacks into this queried data then Fulcro cannot correctly
  refresh the UI in an optimized fashion (because while it can derive the data from the query, it cannot derive the callbacks).
  So, props are for passing data that the component **requested in a query**.

  Fulcro has an additional mechanism for passing things that were not specifically asked for in a query: Computed
  properties.

  For your UI to function properly you must *attach* computed data to props via the helper function `prim/computed`. For
  example, say you were going to render some component through the factory method `ui-other-component`. The props
  for that component would be one map, and the computed would be another. You combined them together into a single
  thing like so:

  ```
  (let [computed-props {:incHandler (fn [args] ...)}
        data-props     { ... }
        props          (prim/computed data-props computed-props)]
    (ui-other-component props))
  ```

  The child can look for these computed properties using `get-computed`.

  ```
  (let [computed-data (prim/get-computed this)]
     ...)
  ```

  and of course Clojure's destructuring allows us to pull out things by key:

  ```
  (let [{:keys [incHandler] :as computed-data} (prim/get-computed this)]
     ...)
  ```

  For convenience `defsc` puts these into an optional third argument that you can also destructure:

  ```
  (defsc MyComponent [this props {:keys [incHandler]}]
    ...)
  ```

  The code below shows a complete running component that is used in the card that follows, and receives it's callbacks
  through the computed properties.

  "
  (dc/mkdn-pprint-source Root-computed)
  (dc/mkdn-pprint-source ui-root-computed))

(defcard passing-callbacks-via-computed
  "This card is backing the React system with it's own atom-based state. By passing in callbacks that modify the
  card state, the UI updates. This is similar to the mechanism used by many other React systems. Fulcro has a different idea. "
  (fn [data-atom-from-devcards _]
    (let [prop-data     @data-atom-from-devcards
          sideband-data {:incHandler  (fn [] (swap! data-atom-from-devcards update-in [:number] inc))
                         :boolHandler (fn [] (swap! data-atom-from-devcards update-in [:b] not))}]
      (ui-root-computed (prim/computed prop-data sideband-data))))
  {:number 42 :people [{:name "Sally"}] :b false}
  {:inspect-data true
   :history      true})

(defsc SimpleCounter [this props]
  {:initLocalState (fn [] {:counter 0})}
  (dom/div nil
    (dom/button #js {:onClick #(prim/update-state! this update :counter inc)}
      "Increment me!")
    (dom/span nil
      (prim/get-state this :counter))))

(def simple-counter (prim/factory SimpleCounter))

(defcard-doc
  "
  ## Component state

  Before we move on to the next section, we should consider component state in a broader scope. Most often,
  a component works on data passed in props. However, you may have an animation or other UI-transient concern
  that requires no props, but still need some kind of state. Fulcro does give you access to React's component
  local state mechanism for these purposes.

  Since component local state and props both have a place in the picture, it is important to understand the
  distinction. Props come from \"above\" (eventually from your central application state database).
  Component local state is encapsulated within a component and is completely transient (lost when the component unmounts).
  Mutating component local state will force a refresh of that component, and has lower overhead, so it is great
  for things like drag-and-drop, animation tracking, etc.
  But component local state opens the door to mutation in-place, and is therefore much harder to reason about when
  debugging and building an application, is invisible to the support viewer, and isn't normalized (sharing it
  involves external manual coordination).

  As you will see later in the tutorial, a normalized global app state database can be combined with component-local
  queries to actually boost local reasoning beyond what you get with embedded state.

  ### Controlled inputs

  React allows for controlled and uncontrolled inputs. The former will not change unless their value changes. The latter
  are more like traditional form fields (your app state is disconnected from their visible value). Controlled form inputs follow
  your state, and are therefore much easier to reason about and debug (they can't do anything visual that differs from
  the \"reality\" of your state). Uncontrolled inputs don't have as much overhead on keystrokes.

  Controlled inputs are generally created by supplying them with a `:value`, and then evolving your app state via
  the input events so that the value you supply changes. This locks your application state with the rendered state.
  Read up on [React forms](https://reactjs.org/docs/forms.html) for more details.

  In general, we recommend using the controlled version to improve your chances of writing bug-free code.

  ### Using local state

  There are a few notable cases where component-local state is useful (or even necessary):

  - Dealing with DOM: Storing the real JS DOM object like an image for interactive drawing.
  - Tracking animations.
  - External library integration: D3 visualizations, integrating with CKEditor, etc.

  Fulcro provides methods for accessing and setting the local state of a
  component. The (optional) `initLocalState` method should return a map of values that will be used for local state
  when the component is first mounted. You can access those values via
  `prim/get-state`, `prim/set-state!`, `prim/update-state!`, and `prim/react-set-state!`. The `set-state!` and
  `update-state!` methods defers the state update and rendering until the next animation frame. This avoids lots
  of spurious DOM updates, but will also incur a query overhead and may not be fast enough for some uses.
  Calling `react-set-state!` affects the component immediately and updates the DOM, but if you trigger a number of them
  (say accidentally through a callback chain) you may generate a firestorm of DOM changes (with possible duplicate updates) instead
  of a clean single refresh."

  (dc/mkdn-pprint-source SimpleCounter)
  (dc/mkdn-pprint-source simple-counter))

(defcard simple-counter-component
  "This card shows a component that changes state known only to the component itself"
  (fn [state-atom _]
    (dom/div nil
      (simple-counter @state-atom))))

(defcard-doc
  "

  ## Important notes and further reading

  - Remember to use `#js` to transform attribute maps for passing to low-level DOM elements.
  - Use *cljs* maps as input to your own Elements: `(my-ui-thing {:a 1})` and Javascript objects for low-level DOM `(dom/div #js { :data-x 1 } ...)`.
  - Add parent-generated things (like callbacks) using `prim/computed` and use the optional third argument to receive them.

  You may do additional [UI exercises](#!/fulcro_tutorial.B_UI_Exercises), or continue on to the
  [next chapter](#!/fulcro_tutorial.C_App_Database) on the client database.
  ")
