(ns com.fulcrologic.fulcro.algorithms.react-interop
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.dom :as dom]
    [taoensso.timbre :as log]))

(defn react-factory
  "Returns a factory for raw JS React classes.

  ```
  (def ui-thing (react-factory SomeReactLibComponent))

  ...
  (defsc X [_ _]
    (ui-thing {:value 1}))
  ```

  The returned function will accept CLJS maps as props (not optional) and then any number of children. The CLJS props
  will be converted to js for interop. You may pass js props as an optimization."
  [js-component-class]
  (fn [props & children]
    #?(:cljs
       (apply js/React.createElement
         js-component-class
         (dom/convert-props props)
         children))))

(defn react-input-factory
  "Returns a factory for raw JS React class that acts like an input. Use this on custom raw React controls are
  controlled via :value to make them behave properly with Fulcro.

  ```
  (def ui-thing (react-input-factory SomeInputComponent))

  ...
  (defsc X [_ _]
    (ui-thing {:value 1}))
  ```

  The returned function will accept CLJS maps as props (not optional) and then any number of children. The CLJS props
  will be converted to js for interop. You may pass js props as an optimization."
  [js-component-class]
  #?(:cljs
     (let [factory (dom/wrap-form-element js-component-class)]
       (fn [props & children]
         (apply factory (clj->js props) children)))
     :default
     (fn [props & children])))

(defn hoc-wrapper-factory
  "Creates a React factory `(fn [parent fulcro-props & children])` for a component that has had an HOC applied,
  and passes Fulcro's parent/props through to 'fulcro_hoc$parent' and 'fulcro_hoc_childprops' in the js props.

  See hoc-factory, which is more likely what you want, as it further wraps the parent context for proper interop."
  [component-class]
  (fn [this props & children]
    (when-not (comp/component? this)
      (log/error "The first argument to an HOC factory MUST be the parent component instance."))
    #?(:cljs
       (apply js/React.createElement
         component-class
         #js {"fulcro_hoc$parent"     this
              "fulcro_hoc$childprops" props}
         children))))

(defn hoc-factory
  "Returns a (fn [parent-component props & children] ...) that will render the target-fulcro-class, but as
  wrapped by the `hoc` function.

  Use this when you have a JS React pattern that tells you:

  ```
  var WrappedComponent = injectCrap(Component);
  ```

  where `injectCrap` is the `hoc` parameter to this function.

  Any injected data will appear as `:injected-props` (a js map) in the computed parameter of the target Fulcro component.

  You can this use the function returned from `hoc-factory` as a normal component factory in fulcro.
  "
  [target-fulcro-class hoc]
  (when-not (comp/component-class? target-fulcro-class)
    (log/error "hoc-factory MUST be used with a Fulcro Class"))
  (let [target-factory         (comp/computed-factory target-fulcro-class)
        target-factory-interop (fn [js-props]
                                 (let [parent       (comp/isoget js-props "fulcro_hoc$parent")
                                       fulcro-props (comp/isoget js-props "fulcro_hoc$childprops")]
                                   (comp/with-parent-context parent
                                     (target-factory fulcro-props {:injected-props js-props}))))
        factory                (let [WrappedComponent (hoc target-factory-interop)]
                                 (hoc-wrapper-factory WrappedComponent))]
    factory))
