(ns fulcro-devguide.B-UI
  (:require-macros
    [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            cljsjs.d3
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(defui Widget
  Object
  (render [this]
    (dom/div nil "Hello world")))

(defui WidgetWithHook
  Object
  (componentWillUpdate [this nextprops nextstate] (println "Component will update"))
  (render [this]
    (dom/div nil "Hello world")))

(def widget (prim/factory Widget))

(defui WidgetWithProperties
  Object
  (render [this]
    (let [{:keys [name]} (prim/props this)]
      (dom/div nil (str "Hello " name)))))

(def prop-widget (prim/factory WidgetWithProperties))


(defcard-doc
  "
  # UI

  NOTE - Namespace aliases used in this document:

```clojure
(require '[fulcro.client.primitives :as prim :refer-macros [defui]]
         '[fulcro.client.dom :as dom])
```

  Fulcro uses <a href=\"https://facebook.github.io/react/index.html\" target=\"_blank\">React</a> underneath.
  The primary mechanism for creating components is the `defui` macro:"
  (dc/mkdn-pprint-source Widget)
  "This macro generates a React class as a plain JavaScript class, so it is completely compatible with the
  React ecosystem.

  Notice the use of `Object`. It indicates that the following method bodies (like in <a
  href=\"http://clojure.org/reference/protocols\" target=\"_blank\">protocols</a>) are being added to the
  generated class. From an OO perspective, this is like saying \"my widget extends Object\". The `render`
  method is the only method you need, but you can also add in your own methods or React lifecycle
  methods.

  NOTE - When implementing the render function, the last top level element will be returned.

  ```clojure
  (render [this]
    (dom/div ...)  ;; <- This will not be returned.
    (dom/div ...)) ;; <- This will.
  ```

  So make sure that you only have one top level element per component

  The render method should be a pure function whenever possible (avoiding component
  local state). Pure rendering is one of the secrets to simplicity.
  Making your rendering pure means that if you ever feel the need to write
  tests around how the UI works (say, for acceptance testing) then you can do so very easily. The lack
  of local state means that the code tends to be so simple as to avoid most of the bugs that plague other
  frameworks.

  ## React lifecycle methods

  If you wish to provide <a href=\"https://facebook.github.io/react/docs/component-specs.html#lifecycle-methods\"
  target=\"_blank\">lifecycle methods</a>, you can define them under the Object section of the UI:
  "
  (dc/mkdn-pprint-source WidgetWithHook)
  "

  The method signatures can be found in the [cheat sheet](https://github.com/fulcrologic/fulcro/blob/develop/docs/CheatSheet.adoc).

  ## ClojureScript and React – HTML differences

  Here are some common things you'll want to know how to do that are different when rendering with Fulcro:

  - Inline styles are specified with real maps `(dom/p #js { :style #js {:backgroundColor \"rgb(120,33,41)\"} } ...)`.
    - `#js` is a reader tag. The reader is the thing that reads your source code, and it is configurable. This one
    tells the reader how to turn the map into a js object.
    - `dom/p` is a macro for generating p tags for use in React componenets. There is one of these for every legal HTML tag.
    - There are alternatives to this ([sablono](https://github.com/r0man/sablono) is popular). It adds some overhead, but many
    people prefer how it reads.
  - Attributes follow the react naming conventions for [Tags and Attributes](https://facebook.github.io/react/docs/tags-and-attributes.html)
    - As an example - CSS class names are specified with `:className` instead of `:class`.
  - Any time there are adjacent elements of the same type in the DOM, they should each have a unique `:key`
  attribute. If the elements are in an array, React requires it.

  ## Element factory

  In order to render components on the screen you need an element factory.
  You generate a factory with `prim/factory`, which will then
  act like a new 'tag' for your DOM:"

  (dc/mkdn-pprint-source widget)

  "Since they are plain React components you can render them in a <a href=\"https://github.com/bhauman/devcards#devcards\"
  target=\"_blank\">devcard</a>, which makes fine tuning them as pure UI dead simple:

  ```
  (defcard widget-card (widget {}))
  ```

  The resulting card looks like this:"
  )

(defcard widget-card (widget {}))

(defcard-doc
  "Such components are known as \"stateless components\" because they do not expliticly ask for data. Later,
  when we learn about colocated queries you'll see it is possible for a component to ask for the data it needs in
  a declarative fashion.

  For now, understand that you *can* give data to a stateless component via a simple edn map, and pull it out
  using `prim/props`:"
  (dc/mkdn-pprint-source WidgetWithProperties)
  (dc/mkdn-pprint-source prop-widget)
  "
  ```
  (defcard props-card (prop-widget {:name \"Sam\"}))
  ```
  ")

(defcard props-card (prop-widget {:name "Sam"}))

(defui Person
  Object
  (render [this]
    (let [{:keys [name]} (prim/props this)]
      (dom/li nil name))))

(def person (prim/factory Person {:keyfn :name}))

(defui PeopleList
  Object
  (render [this]
    (let [people (prim/props this)]
      (dom/ul nil (map person people)))))

(def people-list (prim/factory PeopleList))

(defui Root
  Object
  (render [this]
    (let [{:keys [people number]} (prim/props this)]
      (dom/div nil
               (dom/span nil (str "My lucky number is " number " and I have the following friends:"))
               (people-list people)))))

(def root (prim/factory Root))

(defcard-doc
  "
  ## Composing the UI

  Composing these is pretty straightforward: pull out the bits from props, and pass them on to subcomponents.
  "
  (dc/mkdn-pprint-source Person)
  (dc/mkdn-pprint-source PeopleList)
  (dc/mkdn-pprint-source Root)
  (dc/mkdn-pprint-source people-list)
  (dc/mkdn-pprint-source person)
  (dc/mkdn-pprint-source root)
  "

  ```
  (defcard root-render (root {:number 52 :people [{:name \"Sam\"} {:name \"Joe\"}]}))
  ```

  It is important to note that _this is exactly how the composition of UI components always happens_, independent of
  whether or not you use the rest of the features of Fulcro. A root component calls the factory functions of subcomponents
  with an edn map as the first argument. That map is accessed using `prim/props` on `this` within the subcomponent. Data
  is passed from component to component through `props`. All data originates from the root.

  ### Don't forget the React DOM key!

  You might notice something new here: the `prim/factory` function is supplied with an additional map `{:keyfn :name}`.
  The factory function can be optionally supplied with `:keyfn` that is used to produces the
  <a href=\"https://facebook.github.io/react/docs/multiple-components.html\" target=\"_blank\">React key property</a>
  from component props (here it's using `:name` as a function, which means \"look up :name in props\").

  The key is critical, as it helps React figure out the DOM diff. If you see warning about elements missing
  keys (or having the same key) it means you have adjacent elements in the DOM of the same type, and React cannot
  figure out what to do to make sure they are properly updated.

  ## Play with it

  At this point (if you have not already) you should play with the code in `B-UI.cljs`. Search for `root-render`
  and then scan backwards to the source. You should try adding an object to the properties (another person),
  and also try playing with editing/adding to the DOM.
  ")

(defcard root-render (root {:number 52 :people [{:name "Sam"} {:name "Joe"}]}))

(defui Root-computed
  Object
  (render [this]
    (let [{:keys [people number b]} (prim/props this)
          {:keys [incHandler boolHandler]} (prim/get-computed this)]
      (dom/div nil
               (dom/button #js {:onClick #(boolHandler)} "Toggle Luck")
               (dom/button #js {:onClick #(incHandler)} "Increment Number")
               (dom/span nil (str "My " (if b "" "un") "lucky number is " number
                                  " and I have the following friends:"))
               (people-list people)))))

(def root-computed (prim/factory Root-computed))

(defcard-doc
  "
  ## Out-of-band data: Callbacks and such

  In plain React, you (optionally) store component-local state and pass data from the parent to the child through props.
  Fulcro is no different, though component-local state is discourages for everything except transient things like tracking animations.
  In React, you also pass your callbacks through props. In Fulcro, we need a slight variation of
  this.

  In Fulcro, a component can have a [query](/index.html#!/fulcro_devguide.D_Queries) that asks
  the underlying system for data. If you complect callbacks and such with this queried data then Fulcro cannot correctly
  refresh the UI in an optimized fashion (because while it can derive the data from the query, it cannot derive the callbacks).
  So, props are for passing data that the component **requested in a query**.

  Fulcro has an additional mechanism for passing things that were not specifically asked for in a query: Computed
  properties.

  For your UI to function properly you must *attach* computed data to props via the helper function `prim/computed`.
  The child can look for these computed properties using `get-computed`.
  "

  (dc/mkdn-pprint-source Root-computed)
  (dc/mkdn-pprint-source root-computed)

  "
  ## Play with it!

  Open `B-UI.cljs`, search for `passing-callbacks-via-computed`, and you'll find the card shown below. Interact with it
  in your browser, play with the source, and make sure you understand everything we've covered so far.
  ")

(defcard passing-callbacks-via-computed
         (fn [data-atom-from-devcards _]
           (let [prop-data @data-atom-from-devcards
                 sideband-data {:incHandler  (fn [] (swap! data-atom-from-devcards update-in [:number] inc))
                                :boolHandler (fn [] (swap! data-atom-from-devcards update-in [:b] not))}
                 ]
             (root-computed (prim/computed prop-data sideband-data))))
         {:number 42 :people [{:name "Sally"}] :b false}
         {:inspect-data true
          :history      true})

(defui SimpleCounter
  Object
  (initLocalState [this]
                  {:counter 0})
  (render [this]
     (dom/div nil
              (dom/button #js {:onClick #(prim/update-state! this update :counter inc)}
                          "Increment me!")
              (dom/span nil
                        (prim/get-state this :counter)))))

(def simple-counter (prim/factory SimpleCounter))

(defn render-squares [component props]
  (let [svg (-> js/d3 (.select (dom/node component)))
        data (clj->js (:squares props))
        selection (-> svg
                      (.selectAll "rect")
                      (.data data (fn [d] (.-id d))))]
    (-> selection
        .enter
        (.append "rect")
        (.style "fill" (fn [d] (.-color d)))
        (.attr "x" "0")
        (.attr "y" "0")
        .transition
        (.attr "x" (fn [d] (.-x d)))
        (.attr "y" (fn [d] (.-y d)))
        (.attr "width" (fn [d] (.-size d)))
        (.attr "height" (fn [d] (.-size d))))
    (-> selection
        .exit
        .transition
        (.style "opacity" "0")
        .remove)
    false))

(defui D3Thing
  Object
  (componentDidMount [this] (render-squares this (prim/props this)))
  (shouldComponentUpdate [this next-props next-state] false)
  (componentWillReceiveProps [this props] (render-squares this props))
  (render [this]
    (dom/svg #js {:style   #js {:backgroundColor "rgb(240,240,240)"}
                  :width   200 :height 200
                  :viewBox "0 0 1000 1000"})))

(def d3-thing (prim/factory D3Thing))

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
  Mutating component local state will force a rerender of that component, and has very low overhead, so it is great
  for things like drag-and-drop, animation tracking, etc.
  But component local state opens the door to mutation in-place, and is therefore much harder to reason about when
  debugging and building an application, is invisible to the support viewer, and isn't normalized (sharing it
  involves manual coordination).

  As you will see later in the guide, a normalized global app state database can be combined with component-local
  queries to actually boost local reasoning beyond what you get with embedded state.

  ### Controlled inputs

  React allows for controlled and uncontrolled inputs. The former will not change unless their value changes. The latter
  are more like traditional form fields (your app state is disconnected from their visible value). Controlled form inputs follow
  your state, and are therefore much easier to reason about and debug (they can't do anything visual that differs from
  the \"reality\" of your state). Uncontrolled inputs don't have as much overhead on keystrokes.

  Controlled inputs are generally created by supplying them with a `:value`, and then evolving your app state via
  the input events so that the value you supply changes. This locks your application state with the rendered state.
  Read about React forms on that project's website for more details.

  In general, we recommend using the controlled version to ensure bug-free code.

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
  (fn [state-atom _]
    (dom/div nil
             (simple-counter @state-atom))))

(defcard-doc
  "
  ### External library state (a D3 example)

  Say you want to draw something with D3. D3 has its own DOM diffing algorithms and keeps its own internal state. You must
  make sure React doesn't muck with the DOM. The following component demonstrates how you would go about it.

  First, the actual rendering code that expects the component and the props (which have to
  be converted to JS data types to work. See further D3 tutorials on the web):
  "

  (dc/mkdn-pprint-source render-squares)

  "And the component itself:"

  (dc/mkdn-pprint-source D3Thing)
  (dc/mkdn-pprint-source d3-thing)

  "Here it is integrated into a dev card with some controls to manipulate the data:")


(defn random-square []
  {
   :id    (rand-int 10000000)
   :x     (rand-int 900)
   :y     (rand-int 900)
   :size  (+ 50 (rand-int 300))
   :color (case (rand-int 5)
            0 "yellow"
            1 "green"
            2 "orange"
            3 "blue"
            4 "black")})

(defn add-square [state] (swap! state update :squares conj (random-square)))

(defcard sample-d3-component
         (fn [state-atom _]
           (dom/div nil
                    (dom/button #js {:onClick #(add-square state-atom)} "Add Random Square")
                    (dom/button #js {:onClick #(reset! state-atom {:squares []})} "Clear")
                    (dom/br nil)
                    (dom/br nil)
                    (d3-thing @state-atom)))
         {:squares []}
         {:inspect-data true})

(defcard-doc
  "

  The things to note for this example are:

  - We override the React lifecycle method `shouldComponentUpdate` to return false. This tells React to never ever call
  render once the component is mounted. D3 is in control of the underlying stuff.
  - We override `componentWillReceiveProps` and `componentDidMount` to do the actual D3 render/update. The former will
   get incoming data changes, and the latter is called on initial mount. Our render method
   delegates all of the hard work to D3.

  ## Important notes and further reading

  - Remember to use `#js` to transform attribute maps for passing to low-level DOM elements.
  - Use *cljs* maps as input to your own Elements: `(my-ui-thing {:a 1})` and `(dom/div #js { :data-x 1 } ...)`.
  - Extract properties with `prim/props`. This is the same for stateful (with queries) or stateless components.
  - Add parent-generated things (like callbacks) using `prim/computed` and pull them from the component with
    `(prim/get-computed (prim/props this)`.

  You may do additional [UI exercises](#!/fulcro_devguide.B_UI_Exercises), or continue on to the
  [next chapter](#!/fulcro_devguide.C_App_Database) on the client database.
  ")
