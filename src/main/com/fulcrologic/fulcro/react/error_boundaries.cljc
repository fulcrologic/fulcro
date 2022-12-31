(ns com.fulcrologic.fulcro.react.error-boundaries
  "A macro and predefined component that can create a boundary above which errors cannot propagate. Read the docstring
  of `error-boundary` carefully. Works with server-side rendering as well, and provides for development-time retries
  to recover after a hot code reload without having to reload the entire page."
  #?(:cljs (:require-macros com.fulcrologic.fulcro.react.error-boundaries))
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(def ^:dynamic *render-error*
  "A `(fn [react-element exception] what-to-render)`. Called in order to render an alternate for UI segments that have crashed.
   Defaults to a simple div containing the error header and message as standard HTML elements."
  (fn [this cause] "There was an error."))

(defsc BodyContainer
  "A very lightweight container to offset errors so that we can use `error-boundary` to actually catch errors
   from subsections of a UI."
  [this {:keys [render]}]
  {:use-hooks? true}
  (comp/with-parent-context this
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
     `(ui-error-boundary {:render #(comp/fragment ~@body)})))

#?(:clj
   (defn error-boundary-clj [body]
     `(try
        ~@body
        (catch Throwable _e# "Error"))))

#?(:clj
   (defmacro error-boundary
     "Wraps the given children in a nested pair of stateless elements that prevent unexpected exceptions from
     propagating up the UI tree. Any unexpected rendering or lifecycle errors that happen will be
     caught and cause an error message to be shown in place of the children.

     The error boundary rendering is very very simple by default. A simple message that says there was an error. This
     is partially because you will always probably want to customize it, and also so there are no dependencies on DOM
     so that this macro is usable in Native, where there is no DOM.

     You can clear an error with component local state (see example below), but unless the problem has been corrected
     it will just fail again. Therefore, clearing the error is probably not useful except during development when a hot
     code reload could actually fix the problem.

     You can also completely take over the error boundary rendering by `binding` or by setting to root binding of *render-error*
     via `set!` in cljs, and `alter-var-root` in Clojure.

     For example, in cljs you would add something like this in your entry point:

     ```
     (ns ...
       (:require
         [com.fulcrologic.fulcro.react.error-boundaries :as eb]))

     ...

     (defn start []
       (set! eb/*render-error*
         (fn [this cause]
           (dom/div
             (dom/h2 :.header \" Unexpected Error \")
             (dom/p \"An error occurred while rendering the user interface. \")
             (dom/p (str cause))
             (when #?(:clj false :cljs goog.DEBUG)
               (dom/button {:onClick (fn []
                                       (comp/set-state! this {:error false
                                                              :cause nil}))} \" Dev Mode: Retry rendering \"))))))
     ```
     "
     [& body]
     (if (enc/compiling-cljs?)
       (error-boundary* body)
       (error-boundary-clj body))))
