(ns fulcro-css.css
  "DEPRECATED. Use the new fulcro.client.css namespace instead."
  (:require [fulcro.client.css :as c]))

(def cssify c/cssify)
(def fq-component c/cssify)
(def local-class c/local-class)
(def set-classname c/set-classname)
(def CSS c/CSS)
(def Global c/Global)
(def CSS? c/CSS?)
(def Global? c/Global?)
(def get-global-rules c/get-global-rules)
(def get-local-rules c/get-local-rules)
(def get-includes c/get-includes)
(def get-nested-includes c/get-nested-includes)
(def localize-selector c/localize-selector)
(def localize-css c/localize-css)
(def get-css c/get-css)
(def get-classnames c/get-classnames)
#?(:cljs
   (def style-element c/style-element))
#?(:cljs
   (def remove-from-dom c/remove-from-dom))
#?(:cljs
   (def upsert-css c/upsert-css))
