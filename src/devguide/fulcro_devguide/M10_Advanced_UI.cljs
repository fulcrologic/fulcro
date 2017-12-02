(ns fulcro-devguide.M10-Advanced-UI
  (:require-macros [cljs.test :refer [is]])
  (:require [cljs.pprint :refer [cl-format]]
            cljsjs.victory
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.ui.clip-tool :as ct]
            [fulcro.ui.elements :as ele]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.client.dom :as dom]
            [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.util :as util]
            [fulcro.client.core :as fc]))

(defn us-dollars [n]
  (str "$" (cl-format nil "~:d" n)))

(defn factory-force-children
  [class]
  (fn [props & children]
    (js/React.createElement class
      props
      (util/force-children children))))

(defn factory-apply
  [class]
  (fn [props & children]
    (apply js/React.createElement
      class
      props
      children)))

(def vchart (factory-apply js/Victory.VictoryChart))
(def vaxis (factory-apply js/Victory.VictoryAxis))
(def vline (factory-apply js/Victory.VictoryLine))
;; (def vchart (js/React.createFactory js/Victory.VictoryChart))
;; (def vaxis (js/React.createFactory js/Victory.VictoryAxis))
;; (def vline (js/React.createFactory js/Victory.VictoryLine))

;; " [ {:year 1991 :value 2345 } ...] "
(defsc YearlyValueChart [this {:keys [label plot-data x-step]}]
  {}
  (let [start-year (apply min (map :year plot-data))
        end-year   (apply max (map :year plot-data))
        years      (range start-year (inc end-year) x-step)
        dates      (clj->js (mapv #(new js/Date % 1 2) years))
        {:keys [min-value
                max-value]} (reduce (fn [{:keys [min-value max-value] :as acc}
                                         {:keys [value] :as n}]
                                      (assoc acc
                                        :min-value (min min-value value)
                                        :max-value (max max-value value)))
                              {}
                              plot-data)
        min-value  (int (* 0.8 min-value))
        max-value  (int (* 1.2 max-value))
        points     (clj->js (mapv (fn [{:keys [year value]}]
                                    {:x (new js/Date year 1 2)
                                     :y value})
                              plot-data))]
    (vchart nil
      (vaxis #js {:label      label
                  :standalone false
                  :scale      "time"
                  :tickFormat (fn [d] (.getFullYear d))
                  :tickValues dates})
      (vaxis #js {:dependentAxis true
                  :standalone    false
                  :tickFormat    (fn [y] (us-dollars y))
                  :domain        #js [min-value max-value]})
      (vline #js {:data points}))))

(def yearly-value-chart (prim/factory YearlyValueChart))

(defcard-doc
  "
  # Advanced UI

  ## Using React components from Fulcro (Om.next)

  One of the great parts about React is the ecosystem. There are some great libraries out there. However,
  the interop story isn't always straight forward. The goal of this section is to make that story a little
  clearer.

  ### Factory functions.

  Integrating React components is fairly straight forward if you have used React from JS before. The curve comes
  having spent time with libraries or abstractions like Om and friends. JSX will also abstract some of this away,
  so it's not just the cljs wrappers.  For a good article explaining some of the concepts read,
  [React Elements](https://facebook.github.io/react/blog/2014/10/14/introducing-react-elements.html)
  The take-aways here are:

  1. If you are importing third party components, you should be importing the `class`, not a factory.

  2. You need to explicitly create the react elements with factories. The relevent js functions are
  [React.createElement](https://facebook.github.io/react/docs/top-level-api.html#react.createelement), and
  [React.createFactory](https://facebook.github.io/react/docs/top-level-api.html#react.createfactory).

  It is very important to consider when using any of these functions - the `children`. JS does not have a
  built in notion of lazy sequences. Clojurescript does. This can create suttle bugs when evaluating the
  children of a component.

  `Om.util/force-children` helps us in this regard by taking a seq and returning a vector. We can use this
  to create our own factory function, much like `React.createFactory`:
  "
  (dc/mkdn-pprint-source factory-force-children)

  "This is great, but you will notice that children in our factory may be missing keys. Because we passed a
  vector in, react wont attach the `key` attribute.  We can solve this problem by using the `apply` function."

  (dc/mkdn-pprint-source factory-apply)

  "Here the apply function will pass the children in as args to `React.createElement`, thus avoiding the `key`
  problem, as well as the issue with lazy sequences.

  Now that we have some background on creating React Elements, it's pretty simple to implement.  Let' look at
  making a chart using [Victory](https://github.com/FormidableLabs/victory). We are going to make a simple line
  chart, with an X axis that contains years, and a Y axis that contains dollar amounts. Really the data is
  irrelavent, it's the implementation we care about.  First lets make factories from our Victory components:
  "

  (dc/mkdn-pprint-source vchart)
  (dc/mkdn-pprint-source vaxis)
  (dc/mkdn-pprint-source vline)

  "Next, lets build our component using `defsc` (see M05-More-Concise-UI):"

  (dc/mkdn-pprint-source YearlyValueChart)

  "You will notice in the above code, that we convert relevant clojure datastructures to JS datastructures via
  `clj->js`. This is important, because a JS library is going to expect that kind of data. Other than that,
  the code is fairly straight forward.  And the final product:")


(defcard sample-victory-chart
  (fn [state-atom _]
    (dom/div nil
      (yearly-value-chart @state-atom)))
  {:label     "Yearly Value"
   :x-step    2
   :plot-data [{:year 1983 :value 100}
               {:year 1984 :value 100}
               {:year 1985 :value 90}
               {:year 1986 :value 89}
               {:year 1987 :value 88}
               {:year 1988 :value 85}
               {:year 1989 :value 83}
               {:year 1990 :value 80}
               {:year 1991 :value 70}
               {:year 1992 :value 80}
               {:year 1993 :value 90}
               {:year 1994 :value 95}
               {:year 1995 :value 110}
               {:year 1996 :value 120}
               {:year 1997 :value 160}
               {:year 1998 :value 170}
               {:year 1999 :value 180}
               {:year 2000 :value 180}
               {:year 2001 :value 200}
               ]}
  {:inspect-data true})

(def minion-image "https://s-media-cache-ak0.pinimg.com/736x/34/c2/f5/34c2f59284fcdff709217e14df2250a0--film-minions-minions-images.jpg")

(defsc ICRoot [this {:keys [ui/react-key ctool]}]
  {:initial-state
          (fn [p] {:ctool (prim/get-initial-state ct/ClipTool {:id        :clipper :aspect-ratio 0.5
                                                               :image-url minion-image})})
   :query [:ui/react-key :ctool]}
  (dom/div #js {:key react-key}
    (ct/ui-clip-tool (prim/computed ctool {:onChange (fn [props] (prim/set-state! this props))}))
    (ct/ui-preview-clip (merge (prim/get-state this) {:filename "minions.jpg"
                                                      :width    100 :height 200}))))

(defcard-doc
  "
  # An Image Clip Tool

  Fulcro has the start of an image clip tool. Right now it is mainly for demonstration purposes, but it is in the main
  library and is a good example of a complex UI component where two components have to talk to each other and share
  image data.

  You should study the source code to get the full details, but here is an overview of the critical facets:

  - ClipTool creates a canvas on which to draw
      - It uses initial state and a query to track the setup (e.g. size, aspect ratio, image to clip)
      - For speed (and because some data is not serializable), it uses component-local state to track current clip region,
        a javascript Image object and the DOM canvas (via React ref) for rendering, and the current active operation (e.g. dragging a handle).
      - The mouse events are essentially handled as updates to the component local state, which causes a local component
      render update.
  - PreviewClip is a React component, but not data-driven (no query). Everything is just passed through props.
      - It technically knows nothing of the clip tool.
      - It expects an :image-object and clip data to be passed in...it just renders it on a canvas.
      - It uses React refs to get the reference to the real DOM canvas for rendering
      - It renders whenever props change
  - A callback on the ClipTool communicates *through* the common parent's component local state. The parent
  will re-render when its state changes, which will in turn force a new set of props to be passed to the preview).

  As you can see, the interaction performance is quite good.
  "
  (dc/mkdn-pprint-source ct/ClipTool)
  (dc/mkdn-pprint-source ct/refresh-image)
  (dc/mkdn-pprint-source ct/PreviewClip)
  (dc/mkdn-pprint-source ICRoot))

(defcard-fulcro image-clip
  ICRoot
  {}
  {:inspect-data true})

(defcard-doc
  "# Shadow DOM

  Fulcro includes a helper for creating an isolated shadow DOM in which all or part of your DOM can live. This is useful
  for embedding Fulcro applications within outer applications where you want the DOM and style rules to not collide.

  NOTE: At the present time Shadow DOM is not supported by one or more browsers (notably Firefox). There are polyfills
  available whose performance may be rather terrible.

  ")

(defmutation bump-up [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:child/by-id id :n] inc)))
(defmutation bump-down [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:child/by-id id :n] dec)))

(defsc ListItem [this {:keys [id n]}]
  {:initial-state {:id :param/id :n :param/n}
   :query         [:id :n]
   :ident         [:child/by-id :id]}
  (dom/li #js {:className "item"}
    (dom/span nil "n: " n)
    (dom/button #js {:onClick #(prim/transact! this `[(bump-up {:id ~id})])} "Increment")
    (dom/button #js {:onClick #(prim/transact! this `[(bump-down {:id ~id})])} "Decrement")))

(def ui-list-item (prim/factory ListItem {:keyfn :id}))

(defsc ItemList [this {:keys [title min max items] :or {min 0 max 1000000}}]
  {:initial-state {:id 1 :title "My List" :min 1 :max 1000 :items [{:id 1 :n 2} {:id 2 :n 5} {:id 3 :n 7}]}
   :query         [:id :title :max :min {:items (prim/get-query ListItem)}]
   :ident         [:list/by-id :id]}
  (dom/div #js {:style {:float "left" :width "300px"}}
    (dom/h4 nil (str title (when (= 0 min) (str " (n <= " max ")"))))
    (dom/ul nil (->> items
                  (filter (fn [{:keys [n]}] (<= min n max)))
                  (map ui-list-item)))))

(def ui-list (prim/factory ItemList {:keyfn :id}))

(defsc SDRoot [this {:keys [ui/react-key lists]}]
  {:initial-state {:lists [{}]}
   :query         [:ui/react-key {:lists (prim/get-query ItemList)}]}
  (dom/div nil
    (dom/style #js {} ".item {font-size: 40pt}")
    (ele/ui-shadow-dom {:open-boundary? false}
      (dom/div #js {:key react-key}
        (dom/style #js {} ".item {color: red}")
        (map ui-list lists)))
    (dom/div #js {:className "item"} "sibling")))

(defcard-fulcro shadow-dom
  "This card is running with shadow dom. There is a top-level CSS style to set the font size
  of the 'item' class to 40 points. The list (which has things with the 'item' class) is embedded in a shadow dom, and
  has it's own style section with a class 'item' that is drawn in red.

  The 'sibling' is a div (with class 'item') next to
  the shadow dom. On browsers with a working shadow dom you should see the font size
  (but not color) apply to 'sibling', and you should see the color (but not font size)
  apply to the items in list.

  If your browser does not support shadow DOM, then you will only see the sibling."
  SDRoot)
