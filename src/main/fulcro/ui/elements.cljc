(ns fulcro.ui.elements
  (:require [fulcro.client.primitives :as prim :refer [defui defsc]]
    #?(:cljs [fulcro.client.dom :as dom]
       :clj
            [fulcro.client.dom-server :as dom])
    #?(:cljs [goog.object :as gobj])))

(defn react-instance?
  "Returns the react-instance (which is logically true) iff the given react instance is an instance of the given react class.
  Otherwise returns nil."
  [react-class react-element]
  {:pre [react-class react-element]}
  ; TODO: this isn't quite right
  (when (= (prim/react-type react-element) react-class)
    react-element))

(defn first-node-of-type
  "Finds (and returns) the first child that is an instance of the given React class (or nil if not found)."
  [react-class sequence-of-react-instances]
  (some #(react-instance? react-class %) sequence-of-react-instances))

#?(:cljs
   (defn update-frame-content [this child]
     (let [frame-component (gobj/get this "frame-component")]
       (when frame-component
         (js/ReactDOM.render child frame-component)))))

#?(:cljs
   (defn start-frame [this]
     (let [frame-body (.-body (.-contentDocument (js/ReactDOM.findDOMNode this)))
           {:keys [child]} (prim/props this)
           e1         (.createElement js/document "div")]
       (when (= 0 (gobj/getValueByKeys frame-body #js ["children" "length"]))
         (.appendChild frame-body e1)
         (gobj/set this "frame-component" e1)
         (update-frame-content this child)))))

#?(:cljs
   (prim/defui IFrame
     Object
     (componentDidMount [this] (start-frame this))

     (componentDidUpdate [this _ _]
       (let [child (:child (prim/props this))]
         (update-frame-content this child)))

     (render [this]
       (dom/iframe
         (-> (prim/props this)
           (dissoc :child)
           (assoc :onLoad #(start-frame this))
           clj->js)))))

#?(:cljs
   (let [factory (prim/factory IFrame)]
     (defn ui-iframe [props child]
       (factory (assoc props :child child)))))

#?(:cljs
   (defui ShadowDOM
     Object
     (shouldComponentUpdate [this np ns] true)
     (componentDidMount [this]
       (let [dom-node (dom/node this)
             {:keys [open-boundary? delegates-focus?] :or {open-boundary?   true
                                                           delegates-focus? false}} (prim/props this)
             root     (when (exists? (.-attachShadow dom-node))
                        (.attachShadow dom-node #js {:mode           (if open-boundary? "open" "closed")
                                                     :delegatesFocus delegates-focus?}))]
         (prim/react-set-state! this {:root root})))
     (componentDidUpdate [this pp ps]
       (let [children    (prim/children this)
             shadow-root (prim/get-state this :root)
             child       (first children)]
         (if (and shadow-root child)
           (dom/render child shadow-root))))
     (render [this]
       ; placeholder node to attach shadow DOM to
       (dom/div {:style {:fontSize "14pt" :color "red"}} "Your browser does not support shadow DOM. Please try Chrome or Safari."))))

#?(:clj
   (defn ui-shadow-dom [props children]
     (apply dom/div props children))
   :cljs
   (def ui-shadow-dom
     "Create a div with a shadow DOM, and render the given child (singular) within that root.

     EXPERIMENTAL!!!

     WARNING: Some browsers may require a polyfill to enable shadow DOM. See also `ui-iframe`.

     Props:

     :open-boundary? : Should the outer DOM have js access to the shadow one? (default true)
     :delegates-focus? : Should focus be delegated? (default false)
     "
     (prim/factory ShadowDOM)))
