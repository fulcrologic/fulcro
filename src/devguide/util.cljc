(ns styles.util
  #?(:clj
     (:require [devcards.core :as dc :include-macros true]
               cljs.repl
               [clojure.string :as str])
     :cljs
     (:require [devcards.core :as dc :include-macros true]
       [clojure.set :as set]
       [clojure.string :as str]
       [hickory.core :as hc]
       [untangled.client.mutations :as m]
       [untangled.client.core :as uc]
       [untangled.ui.elements :as e]
       [om.next :as om :refer [defui]]
       [om.dom :as dom]
       [devcards.util.markdown :as md]
       [devcards.util.edn-renderer :as edn])))


