(ns fulcro-css.css-injection
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [clojure.spec.alpha :as s]
            [garden.core :as g]
            [fulcro-css.css :as css]
            [fulcro.logging :as log]
            #?(:cljs [fulcro.client.dom :as dom]
               :clj  [fulcro.client.dom-server :as dom])))

(defn find-css-nodes
  "Scan the given component and return an ordered vector of the css rules in depth-first order.
  `order` can be :depth-first (default) or :breadth-first"
  [{:keys [component order state-map]}]
  (let [query         (if (map? state-map) (prim/get-query component state-map) (prim/get-query component))
        ast           (prim/query->ast query)
        breadth       (atom 0)
        traverse      (fn traverse* [{:keys [children component] :as ast-node} depth]
                        (into
                          (if (and component (css/CSS? component))
                            [{::depth     depth
                              ::breadth   (swap! breadth inc)
                              ::component component}]
                            [])
                          (mapcat #(traverse* % (inc depth)) (seq children))))
        nodes         (traverse ast 0)
        ordered-nodes (if (= order :breadth-first)
                        (sort-by ::breadth nodes)
                        (sort-by #(- (::depth %)) nodes))
        unique-nodes  (distinct (map ::component ordered-nodes))]
    (when-not query
      (log/error "Auto-include was used for CSS, but the component had no query! No CSS Found."))
    unique-nodes))

(s/fdef find-css-nodes
  :args (s/cat :options map?)
  :ret seq?)

(let [get-rules (fn [components] (reduce #(into %1 (css/get-css-rules %2)) [] components))]
  (defn compute-css
    "Compute the stringified CSS based on the given props. This can be used to generate a
    server-side version of CSS for the initial DOM, and is used the other injection functions to compute
    the CSS.

    Props are as described in `style-element`.
    "
    [props]
    (let [{:keys [component auto-include?]} props
          rules (if (false? auto-include?)
                  (some-> component (css/get-css))
                  (some-> (find-css-nodes props) get-rules))
          css   (g/css rules)]
      css)))

(defsc StyleElement [this {:keys [order key]}]
  {:componentDidMount (fn []
                        (let [css (compute-css (prim/props this))]
                          (prim/set-state! this {:css css})))}
  ;; This ensures best performance. React doesn't check/diff it this way.
  (dom/style {:dangerouslySetInnerHTML {:__html (prim/get-state this :css)}}))

(let [factory (prim/factory StyleElement)]
  (defn style-element
    "Renders a style element. Valid props are:

     - `:component`: (REQUIRED) The UI component to pull CSS from. Class or instance allowed.
     - `:order`: (optional)  `:depth-first` (default) or `:breadth-first` (legacy order)
     - `:react-key` : (optional) A React key. Changing the key will force it to update the CSS (which is otherwise caches for performance)
     - `:auto-include?`: (optional) When set to true (default) it will use the component query to recursively scan for
       CSS instead of explicit includes. When set to (exactly) `false` then it ONLY uses the user-declared inclusions on
       the component.

    The resulting React style element avoids re-rendering unless the props change, and the CSS is cached at component mount; therefore
    this element will avoid all overhead on refresh. In development you may wish to have the CSS change on hot code reload, in which case
    you can simply change the `:react-key` on the props to force a re-mount (which will recompute the CSS).
    "
    [props]
    (let [component (:component props)
          props     (cond-> props
                      (prim/component? component) (->
                                                    (assoc :state-map (some-> component prim/component->state-map))
                                                    (update :component prim/react-type)))]
      #?(:cljs (factory props)
         :clj  (dom/style {}
                 (compute-css props))))))

#?(:cljs
   (defn upsert-css
     "(Re)place the STYLE element with the provided ID on the document's low-level DOM with the co-located CSS of
     the specified component.

     The `options` is the same as passed to `style-element`."
     [id options]
     (css/remove-from-dom id)
     (let [style-ele (.createElement js/document "style")
           css       (compute-css options)]
       (set! (.-innerHTML style-ele) css)
       (.setAttribute style-ele "id" id)
       (.appendChild (.-body js/document) style-ele))))
