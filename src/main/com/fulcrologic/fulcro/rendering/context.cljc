(ns com.fulcrologic.fulcro.rendering.context
  #?(:cljs (:require-macros com.fulcrologic.fulcro.rendering.context))
  #?(:cljs (:require ["react" :as react]))
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
       `(create-element Provider ~(JSValue. {:value cmap}) ~child)
       `(binding [rendering-context ~cmap] ~child)))))

(defn in-context
  "Call `f` to render children, where `f` will receive the current rendering context as a parameter. Use `context-props`
   to pass props to the React/Consumer element (most importantly a key, if in a list)."
  ([context-props f]
   #?(:clj  (f rendering-context)
      :cljs (create-element Consumer (clj->js context-props) f)))
  ([f]
   #?(:clj  (f rendering-context)
      :cljs (create-element Consumer nil f))))

(defn merge-context
  "Render `(f [context])` in a context where `m` is merged into the one already present."
  [m f]
  #?(:clj
     (binding [rendering-context (merge rendering-context m)]
       (f rendering-context))
     :cljs
     (in-context
       (fn [m-old]
         (let [new-context (merge m-old m)]
           (react/createElement Provider #js {:value new-context} (f new-context)))))))

