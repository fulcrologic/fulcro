(ns com.fulcrologic.fulcro.algorithms.application-helpers
  "This namespace helps with access to details of the application, but with no dependency on the application namespace.
  This prevents circular references since many things need to work with applications, but are themselves used in the
  construction of an application."
  (:require
    [taoensso.timbre :as log]))

(defn app-algorithm
  "Get the current value of a particular Fulcro plugin algorithm.  These are set by default and can be overridden
  when you create your fulcro app.

  `app` - The application
  `k` - (optional) the algorithm to obtain. This can be a plain keyword or a symbol of the algorithm desired.  If this
  is not specified then a map keyed by `:algorithm/name` will be retured.

  Supported algorithms that can be obtained/overridden in Fulcro (check the source of app/fulcro-app if you suspect this is out
  of date):

  - `:algorithm/tx!` - Internal implementation of transaction submission. Default `app/default-tx!`
  - `:algorithm/global-eql-transform` - A `(fn [tx] tx')` that is applied to all outgoing requests (when using default `tx!`).
     Defaults to stripping things like `:ui/*` and form state config joins.
  - `:algorithm/remote-error?` - A `(fn [result] boolean)` that defines what a remote error is.
  - `:algorithm/global-error-action` - A `(fn [env] ...)` that is run on any remote error (as defined by `remote-error?`).
  - `:algorithm/optimized-render!` - The concrete render algorithm for optimized renders (not root refreshes)
  - `:algorithm/render!` - The top-level render function. Calls root render or optimized render by default. Renders on the calling thread.
  - `:algorithm/schedule-render!` - The call that schedules a render. Defaults to using `js/requestAnimationFrame`.
  - `:algorithm/default-result-action` -  The action used for remote results in all mutations that do not have a `result-action` section.
  - `:algorithm/index-root!` - The algorithm that scans the current query from root an indexes all classes by their queries.
  - `:algorithm/index-component!` - The algorithm that adds a component to indexes when it mounts.
  - `:algorithm/drop-component!` - The algorithm that removes a component from indexes when it unmounts.
  - `:algorithm/props-middleware` - Middleware that can modify `props` for all components.
  - `:algorithm/render-middleware` - Middlware that wraps all `render` methods of `defsc` components.
  "
  ([app]
   (get app :com.fulcrologic.fulcro.application/algorithms))
  ([app k]
   (if-let [nm (cond
                 (or (keyword? k) (symbol? k)) (keyword "algorithm" (name k))
                 (string? k) (keyword "algorithm" k))]
     (get-in app [:com.fulcrologic.fulcro.application/algorithms nm]
       (fn [& any]
         (throw (ex-info "Missing algorithm: " {:name nm})))))))
