(ns fulcro-css.css
  (:require [cljs.tagged-literals]
            [fulcro-css.css-protocols :as cp :refer [local-rules include-children global-rules]]
            [fulcro-css.css-implementation :as ci]
            [clojure.string :as str]
            #?(:cljs [cljsjs.react.dom])
            [clojure.walk :as walk]
            [garden.core :as g]
            [garden.selectors :as gs]))

(def cssify "Replaces slashes and dots with underscore." ci/cssify)
(def fq-component ci/fq-component)
(def local-class "Generates a string name of a localized CSS class. This function combines the fully-qualified name of the given class
     with the (optional) specified name."
  ci/local-class)
(def set-classname ci/set-classname)
(def CSS? "`(CSS? class)` : Returns true if the given component has css." ci/CSS?)
(def Global? "`(Global? class)` : Returns true if the component has global rules" ci/Global?)
(def get-global-rules "`(get-global-rules class)` : Get the *raw* value from the global-rules of a component." ci/get-global-rules)
(def get-local-rules "`(get-local-rules class)` : Get the *raw* value from the local-rules of a component." ci/get-local-rules)
(def get-includes "`(get-inculdes class)` :Returns the list of components from the include-children method of a component" ci/get-includes)
(def get-nested-includes "`(get-nested-includes class)` : Recursively finds all includes starting at the given component." ci/get-nested-includes)
(def get-classnames "`(get-classnames class)` : Returns a map from user-given CSS rule names to localized names of the given component." ci/get-classnames)

(defn localize-selector
  [selector comp]
  (let [val                 (:selector selector)
        split-cns-selectors (str/split val #" ")]
    (gs/selector (str/join " " (map #(if (ci/prefixed-name? %)
                                       (ci/localize-name % comp)
                                       %)
                                 split-cns-selectors)))))

(defn localize-css
  "Converts prefixed keywords into localized keywords and localizes the values of garden selectors"
  [component]
  (walk/postwalk (fn [ele]
                   (cond
                     (ci/prefixed-keyword? ele) (ci/localize-kw ele component)
                     (ci/selector? ele) (localize-selector ele component)
                     :otherwise ele)) (get-local-rules component)))

(defn get-css-rules
  "Gets the raw local and global rules from the given component."
  [component]
  (concat (localize-css component)
    (get-global-rules component)))

(defn get-css
  "Recursively gets all global and localized rules (in garden notation) starting at the given component."
  [component]
  (let [own-rules             (get-css-rules component)
        nested-children       (distinct (get-nested-includes component))
        nested-children-rules (reduce #(into %1 (get-css-rules %2)) [] nested-children)]
    (concat own-rules nested-children-rules)))

(defn raw-css
  "Returns a string that contains the raw CSS for the rules defined on the given component's sub-tree. This can be used for
   server-side rendering of the style element, or in a `style` element as the :dangerouslySetInnerHTML/:html value:

   (dom/style #js {:dangerouslySetInnerHTML #js {:__html (raw-css component)}})
   "
  [component]
  (g/css (get-css component)))

#?(:cljs
   (defn style-element
     "Returns a React Style element with the (recursive) CSS of the given component. Useful for directly embedding in your UI VDOM.
     DEPRECATED: Use fulcro-css.css-injection/style-element instead."
     [component]
     (js/React.createElement "style" #js {:dangerouslySetInnerHTML #js {:__html (g/css (get-css component))}})))

#?(:cljs
   (defn remove-from-dom "Remove the given element from the DOM by ID"
     [id]
     (if-let [old-element (.getElementById js/document id)]
       (let [parent (.-parentNode old-element)]
         (.removeChild parent old-element)))))

#?(:cljs
   (defn upsert-css
     "(Re)place the STYLE element with the provided ID on the document's DOM  with the co-located CSS of the specified component.
     DEPRECATED: Use fulcro-css.css-injection/upsert-css instead."
     [id root-component]
     (remove-from-dom id)
     (let [style-ele (.createElement js/document "style")]
       (set! (.-innerHTML style-ele) (g/css (get-css root-component)))
       (.setAttribute style-ele "id" id)
       (.appendChild (.-body js/document) style-ele))))


