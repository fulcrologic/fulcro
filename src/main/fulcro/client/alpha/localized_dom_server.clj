(ns fulcro.client.alpha.localized-dom-server
  (:refer-clojure :exclude [map meta time])
  (:require [fulcro.util :as util]
            [clojure.spec.alpha :as s]
            [clojure.future :refer :all]
            [fulcro.client.alpha.dom-server :refer [element element?]]
            [fulcro.client.alpha.dom-common :as dc]
            [fulcro.client.alpha.localized-dom-common :as ldc]))

(declare a abbr address area article aside audio b base bdi bdo big blockquote body br button canvas caption cite
  code col colgroup data datalist dd del details dfn dialog div dl dt em embed fieldset figcaption figure footer form
  h1 h2 h3 h4 h5 h6 head header hr html i iframe img ins input textarea select option kbd keygen
  label legend li link main map mark menu menuitem meta meter nav noscript object ol optgroup output p param picture
  pre progress q rp rt ruby s samp script section small source span strong style sub summary sup table tbody
  td tfoot th thead time title tr track u ul var video wbr circle clipPath ellipse g line mask path
  pattern polyline rect svg text defs linearGradient polygon radialGradient stop tspan)

(s/def ::dom-element-args
  (s/cat
    :css (s/? keyword?)
    :attrs (s/? (s/or
                  :nil nil?
                  :map #(and (map? %) (not (element? %)))))
    :children (s/* (s/or
                     :string string?
                     :number number?
                     :collection #(or (vector? %) (seq? %))
                     :element element?))))

(defn gen-tag-fn [tag]
  `(defn ~tag [& ~'args]
     (let [conformed-args# (util/conform! ::dom-element-args ~'args)
           {attrs#    :attrs
            children# :children
            css#      :css} conformed-args#
           children#       (mapv second children#)
           attrs-value#    (or (second attrs#) {})]
       (element {:tag       '~tag
                 :attrs     (-> attrs-value#
                              (dissoc :ref :key)
                              (ldc/add-kwprops-to-props css#))
                 :react-key (:key attrs-value#)
                 :children  children#}))))

(defmacro gen-all-tags []
  (when-not (boolean (:ns &env))
    `(do
       ~@(clojure.core/map gen-tag-fn dc/tags))))

(gen-all-tags)
