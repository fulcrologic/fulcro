(ns com.fulcrologic.fulcro.component-middleware
  (:require
    #?(:cljs [goog.object :as gobj])))

(defn wrap-update-extra-props
  "Wrap the props middleware such that `f` is called to get extra props that should be placed
  in the extra-props arg of the component.

  `handler` - (optional) The next item in the props middleware chain.
  `f` - A (fn [cls extra-props] new-extra-props)

  `f` will be passed the class being rendered, and the current map of extra props. It should augment
  those and return a new version. "
  ([f]
   (fn [cls raw-props]
     #?(:clj  false                                         ;; FIXME
        :cljs (let [existing (or (gobj/get raw-props "fulcro$extra_props") {})
                    new      (f cls existing)]
                (gobj/set raw-props "fulcro$extra_props" new)))))
  ([handler f]
   (fn [cls raw-props]
     #?(:clj  false                                         ;; FIXME
        :cljs (let [existing (or (gobj/get raw-props "fulcro$extra_props") {})
                    new      (f cls existing)]
                (gobj/set raw-props "fulcro$extra_props" new)
                (handler cls raw-props))))))
