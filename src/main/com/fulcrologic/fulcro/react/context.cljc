(ns com.fulcrologic.fulcro.react.context
  #?(:cljs (:require-macros com.fulcrologic.fulcro.rendering.context))
  #?(:cljs (:require
             ["react" :as react]
             [goog.object :as gobj]))
  #?(:clj (:import (cljs.tagged_literals JSValue))))

(defn create-context
  "Wrapper for React createContext. Returns a map containing `:ui-consumer` and `:ui-provider` factories, along
   with the raw `:raw-context`. The latter is what you would use with raw React useContext and such.

   The ui-provider accepts a value and a single react element child.

   ```
   (ui-provider 42
     (dom/div ...))
   ```

   The ui-consumer expects to be passed a function (and can accept optional props):

   ```
   (ui-consumer (fn [context-value] react-element))
   (ui-consumer {:key n} (fn [context-value] react-element))
   ```

   "
  ([] (create-context nil))
  ([default-value]
   #?(:cljs
      (let [context  (react/createContext default-value)
            Consumer (.-Consumer context)
            Provider (.-Provider context)]
        {:raw-context context
         :ui-provider (fn [v c]
                        (react/createElement Provider #js {:value v} c))
         :ui-consumer (fn *ui-consumer*
                        ([f] (*ui-consumer* nil f))
                        ([props f]
                         (react/createElement Consumer (clj->js props) f)))})
      :clj
      (let [context (atom default-value)]
        {:raw-context context
         :ui-provider (fn [v c]
                        (let [old-context @context]
                          (reset! context v)
                          (try
                            c
                            (finally
                              (reset! context old-context)))))
         :ui-consumer (fn [f] (f @context))}))))

(defn current-context-value
  "Usable within render and component lifecycle methods. Gets the context value syncrhonously.

   NOTE: May be flaky among different verions of react. Use at your own risk. Recommended that you use `ui-consumer`
   instead."
  [context]
  #?(:clj @(:raw-context context)
     :cljs
     (let [context (if (and (map? context) (contains? context :raw-context))
                     (:raw-context context)
                     context)]
       (or
         (gobj/get context "_currentValue")
         (gobj/get context "_currentValue2")))))

(defn set-class-context!
  "Set the context type on the given component C (component based react)."
  [C context]
  #?(:clj :noop
     :cljs
     (set! (.-contextType ^js C) context)))

(defn use-context
  "React hook, but works with Fulcro-wrapped context from this ns."
  [wrapped-context]
  #?(:clj  (some-> wrapped-context :raw-context deref)
     :cljs (some-> wrapped-context :raw-context (react/useContext))))
