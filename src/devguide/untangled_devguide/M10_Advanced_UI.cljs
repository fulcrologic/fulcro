(ns untangled-devguide.M10-Advanced-UI
  (:require-macros [cljs.test :refer [is]])
  (:require [cljs.pprint :refer [cl-format]]
            [cljsjs.victory]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [om.dom :as dom]
            [om.next :as om :refer-macros [defui]]
            [om.util :as util]))

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
(defui ^:once YearlyValueChart
  Object
  (render [this]
          (let [{:keys [label
                        plot-data
                        x-step]}    (om/props this)
                start-year          (apply min (map :year plot-data))
                end-year            (apply max (map :year plot-data))
                years               (range start-year (inc end-year) x-step)
                dates               (clj->js (mapv #(new js/Date % 1 2) years))
                {:keys [min-value
                        max-value]} (reduce (fn [{:keys [min-value max-value] :as acc}
                                                 {:keys [value] :as n}]
                                              (assoc acc
                                                     :min-value (min min-value value)
                                                     :max-value (max max-value value)))
                                            {}
                                            plot-data)
                min-value           (int (* 0.8 min-value))
                max-value           (int (* 1.2 max-value))
                points              (clj->js (mapv (fn [{:keys [year value]}]
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
                    (vline #js {:data points})))))

(def yearly-value-chart (om/factory YearlyValueChart))

(defcard-doc
  "
  # Advanced UI

  ## Using React components from Untangled (Om.next)

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

  "Next, lets build our `Om.next` component using `defui`:"

  (dc/mkdn-pprint-source YearlyValueChart)

  "You will notice in the above code, that we convert relevant clojure datastructures to JS datastructures via
  `clj->js`. This is important, because a JS library is going to expect that kind of data. Other than that,
  the code is fairly straight forward.  And the final product:"
  )


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
