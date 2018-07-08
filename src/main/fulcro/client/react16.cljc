(ns fulcro.client.react16
  "Support for React v16+"
  #?(:cljs (:require-macros fulcro.client.react16))
  (:require
    #?@(:clj  [clojure.main
               [cljs.core :refer [deftype specify! this-as js-arguments]]
               [clojure.future :refer :all]
               [cljs.util]]
        :cljs [[goog.string :as gstring]
               [cljsjs.react]
               [goog.object :as gobj]])
               fulcro-css.css
               [fulcro.client.impl.protocols :as p]
               [fulcro.util :as util]
               [clojure.walk :refer [prewalk]]
               [fulcro.client.primitives :as prim])
  #?(:clj
     (:import [java.io Writer])))

(def a 1)

