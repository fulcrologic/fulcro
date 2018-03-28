(ns fulcro.client.alpha.localized-dom
  (:refer-clojure :exclude [map meta time])
  (:require
    [fulcro.client.alpha.dom :as adom]
    [fulcro.util :as util]
    [fulcro.client.alpha.localized-dom-common :as cdom]))

(declare a abbr address area article aside audio b base bdi bdo big blockquote body br button canvas caption cite
  code col colgroup data datalist dd del details dfn dialog div dl dt em embed fieldset figcaption figure footer form
  h1 h2 h3 h4 h5 h6 head header hr html i iframe img ins input textarea select option kbd keygen
  label legend li link main map mark menu menuitem meta meter nav noscript object ol optgroup output p param picture
  pre progress q rp rt ruby s samp script section small source span strong style sub summary sup table tbody
  td tfoot th thead time title tr track u ul var video wbr circle clipPath ellipse g line mask path
  pattern polyline rect svg text defs linearGradient polygon radialGradient stop tspan)

(def node fulcro.client.alpha.dom/node)
(def render-to-str fulcro.client.alpha.dom/render-to-str)
(def create-element fulcro.client.alpha.dom/create-element)

(letfn [(arr-append* [arr x] (.push arr x) arr)
        (arr-append [arr tail] (reduce arr-append* arr (util/force-children tail)))]
  (defn macro-create-element
    ([type args] (macro-create-element type args nil))
    ([type args csskw]
     (let [[head & tail] args
           f (if (adom/form-elements? type)
               adom/macro-create-wrapped-form-element
               adom/macro-create-element*)]
       (cond
         (nil? head)
         (f (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
              (arr-append tail)))

         (fulcro.client.alpha.dom/element? head)
         (f (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
              (arr-append args)))

         (object? head)
         (f (doto #js [type (cdom/add-kwprops-to-props head csskw)]
              (arr-append tail)))

         (map? head)
         (f (doto #js [type (clj->js (cdom/add-kwprops-to-props head csskw))]
              (arr-append tail)))

         :else
         (f (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
              (arr-append args))))))))

(fulcro.client.alpha.dom/gen-client-dom-fns fulcro.client.alpha.localized-dom/macro-create-element)
