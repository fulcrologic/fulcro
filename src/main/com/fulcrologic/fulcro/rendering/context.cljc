(ns com.fulcrologic.fulcro.rendering.context
  #?(:cljs (:require-macros com.fulcrologic.fulcro.rendering.context))
  (:require
    [taoensso.timbre :as log]
    #?@(:cljs
        [["react" :as react]
         [goog.object :as gobj]]))
  #?(:clj (:import (cljs.tagged_literals JSValue))))

(defn- force-children
  "Utility function that will force a lazy sequence of children (recursively) into realized
  vectors (React cannot deal with lazy seqs in production mode)"
  [x]
  (cond->> x
    (seq? x) (into [] (map force-children))))

#?(:cljs
   (defn- create-element                                    ; circ ref on dom, so just copied here
     ([tag]
      (create-element tag nil))
     ([tag opts]
      (react/createElement tag opts))
     ([tag opts & children]
      (apply react/createElement tag opts children))))

#?(:cljs
   (defonce rendering-context (react/createContext nil))
   :clj
   (defonce ^:dynamic rendering-context {}))
(defonce Provider #?(:cljs (.-Provider rendering-context) :clj nil))
(defonce Consumer #?(:cljs (.-Consumer rendering-context) :clj nil))

(def root-provider-context-object (memoize (fn [app-id] #js {})))

#?(:clj
   (defmacro ui-provider
     ([app child] `(ui-provider ~app ~child false))
     ([app child force-render?]
      (let [cmap `{:app              ~app
                   :parent           nil
                   :depth            0
                   :shared           (some-> ~app :com.fulcrologic.fulcro.application/runtime-atom deref :com.fulcrologic.fulcro.application/shared-props)
                   :denormalize-time (some-> ~app :com.fulcrologic.fulcro.application/runtime-atom deref :com.fulcrologic.fulcro.application/basis-t)
                   :force-render?    ~force-render?
                   :query-state      (some-> ~app :com.fulcrologic.fulcro.application/state-atom deref)}]
        (if (boolean (:ns &env))
          `(let [cobj# (root-provider-context-object (:com.fulcrologic.fulcro.application/id ~app))
                 arg#  (js/Object.)]
             (goog.object/set cobj# "context" ~cmap)
             (goog.object/set arg# "value" cobj#)
             (com.fulcrologic.fulcro.rendering.context/create-element Provider arg# ~child))
          `(binding [rendering-context ~cmap] ~child))))))

(letfn [(extract [f] (fn [js-obj] #?(:cljs (f (gobj/get js-obj "context")))))]
  (defn in-context
    "Call `f` to render children, where `f` will receive the current rendering context as a parameter. Use `context-props`
     to pass props to the React/Consumer element (most importantly a key, if in a list)."
    ([context-props f]
     #?(:clj  (f rendering-context)
        :cljs (create-element Consumer (clj->js context-props) (extract f))))
    ([f]
     #?(:clj  (f rendering-context)
        :cljs (create-element Consumer nil (extract f))))))
