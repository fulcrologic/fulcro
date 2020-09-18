(ns com.fulcrologic.fulcro.react.error-boundaries
  "A macro and predefined component that can create a boundary above which errors cannot propagate. Read the docstring
  of `error-boundary` carefully. Works with server-side rendering as well, and provides for development-time retries
  to recover after a hot code reload without having to reload the entire page."
  #?(:cljs (:require-macros com.fulcrologic.fulcro.react.error-boundaries))
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    #?(:clj  [com.fulcrologic.fulcro.dom-server :as dom]
       :cljs [com.fulcrologic.fulcro.dom :as dom])
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(def ^:dynamic *render-error*
  "A `(fn [react-element exception] what-to-render)`. Called in order to render an alternate for UI segments that have crashed.
   Defaults to a simple div containing the error header and message as standard HTML elements."
  (fn [this cause]
    (dom/div
      (dom/h2 :.header "Unexpected Error")
      (dom/p "An error occurred while rendering the user interface.")
      (dom/p (str cause))
      (when #?(:clj false :cljs goog.DEBUG)
        (dom/button {:onClick (fn []
                                (comp/set-state! this {:error false
                                                       :cause nil}))} "Dev Mode: Retry rendering")))))

(defsc BodyContainer
  "A very lightweight container to offset errors so that we can use `error-boundary` to actually catch errors
   from subsections of a UI."
  [this {:keys [parent render]}]
  {:use-hooks? true}
  (comp/with-parent-context parent
    (render)))

(def ui-body-container (comp/factory BodyContainer))

(defsc ErrorBoundary [this props]
  {:shouldComponentUpdate    (fn [_np _ns] true)
   :getDerivedStateFromError (fn [error]
                               {:error true
                                :cause error})
   :componentDidCatch        (fn [_this error _info] (log/error (ex-message error)))}
  (let [{:keys [error cause]} (comp/get-state this)]
    (if error
      (*render-error* this cause)
      (ui-body-container props))))

(def ui-error-boundary (comp/factory ErrorBoundary))

#?(:clj
   (defn error-boundary* [body]
     `(ui-error-boundary {:parent comp/*parent*
                          :render #(comp/fragment ~@body)})))

#?(:clj
   (defn error-boundary-clj [body]
     `(try
        ~@body
        (catch Throwable _e#
          (dom/h1 *error-header*)
          (dom/h2 *error-message*)))))

#?(:clj
   (defmacro error-boundary
     "Wraps the given children in a nested pair of stateless elements that prevent unexpected exceptions from
     propagating up the UI tree. Any unexpected rendering or lifecycle errors that happen will be
     caught and cause an error message to be shown in place of the children.

     You can also completely take over the error boundary rendering by `binding` or by setting to root binding of *render-error*
     via `set!` in cljs, and `alter-var-root` in Clojure.
     "
     [& body]
     (if (enc/compiling-cljs?)
       (error-boundary* body)
       (error-boundary-clj body))))
