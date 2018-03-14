(ns fulcro.client.alpha.localized-dom
  (:refer-clojure :exclude [map meta time])
  (:require-macros [fulcro.client.alpha.localized-dom])
  (:require
    [fulcro.client.alpha.dom :as adom]
    [fulcro.client.alpha.localized-dom-common :as cdom]))

(def node adom/node)
(def render-to-str adom/render-to-str)
(def create-element adom/create-element)

(letfn [(arr-append* [arr x] (.push arr x) arr)
        (arr-append [arr tail] (reduce arr-append* arr tail))]
  (defn macro-create-element
    ([type args] (macro-create-element type args nil))
    ([type args csskw]
     (let [[head & tail] args]
       (cond
         (nil? head)
         (fulcro.client.alpha.dom/macro-create-element*
           (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
             (arr-append tail)))

         (object? head)
         (fulcro.client.alpha.dom/macro-create-element*
           (doto #js [type (cdom/add-kwprops-to-props head csskw)]
             (arr-append tail)))

         (map? head)
         (fulcro.client.alpha.dom/macro-create-element*
           (doto #js [type (clj->js (cdom/add-kwprops-to-props head csskw))]
             (arr-append tail)))

         (adom/element? head)
         (fulcro.client.alpha.dom/macro-create-element*
           (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
             (arr-append args)))

         :else
         (fulcro.client.alpha.dom/macro-create-element*
           (doto #js [type (cdom/add-kwprops-to-props #js {} csskw)]
             (arr-append args))))))))
