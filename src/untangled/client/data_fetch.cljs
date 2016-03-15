(ns untangled.client.data-fetch
  (:require
    [clojure.walk :refer [walk prewalk]]
    [om.next :as om]
    [untangled.client.impl.data-fetch :as impl]
    [untangled.i18n :refer-macros [tr]]
    [om.next.impl.parser :as op]
    [om.dom :as dom]))

(defn load-field
  "Load a field of the current component. Runs `om/transact!`.

  Parameters
  - `component`: The component
  - `field`: A field on the current component's query that you wish to load
  - `without`: Named parameter for excluding child keys from the query (e.g. for recursive queries or additional laziness)
  - `params`: Named parameter for adding params to the query sent to the server for this field.
  - `post-mutation`: A mutation (symbol) invoked after the load succeeds.
  "
  [component field & {:keys [without params post-mutation]}]
  (om/transact! component [(list 'app/load
                             {:ident         (om/get-ident component)
                              :field         field
                              :query         (om/focus-query (om/get-query component) [field])
                              :params        params
                              :without       without
                              :post-mutation post-mutation})]))

(defn load-data
  "Load data from the remote. Runs `om/transact!`. See also `load-field`.

  Parameters
  - `comp-or-reconciler`: A component or reconciler (not a class)
  - `query`: The query for the element(s) attributes. Use defui to generate arbitrary queries so normalization will work.
  - Named parameter `ident`: An ident, used if loading a singleton and you wish to specify 'which one'.
  - `post-mutation`: A mutation (symbol) invoked after the load succeeds.

  Named parameters `:without` and `:params` are as in `load-field`.
  "
  [comp-or-reconciler query & {:keys [ident without params post-mutation]}]
  (let []
    (om/transact! comp-or-reconciler [(list 'app/load
                                        {:ident         ident
                                         :query         query
                                         :params        params
                                         :without       without
                                         :post-mutation post-mutation})])))

; DEPRECATED NAMES FOR load-data:
(def load-singleton load-data)
(def load-collection load-data)

(defn load-field-action
  "Queue up a remote load of a component's field from within an already-running mutation. Similar to `load-field`
  but usable from within a mutation.

  To use this function make sure your mutation specifies a return value with a remote. The remote
  should include the abstract mutation `(app/load)` as well as any desired `(tx/fallback)`:

  { :remote (om/query->ast '[(app/load)])
    :action (fn []
       (load-field-action ...)
       ; other optimistic updates/state changes)}"
  [app-state component-class ident field & {:keys [without params post-mutation]}]
  (impl/mark-ready
    :state app-state
    :field field
    :ident ident
    :query (om/focus-query (om/get-query component-class) [field])
    :params params
    :without without
    :post-mutation post-mutation))

(defn load-data-action
  "Queue up a remote load from within an already-running mutation. Similar to `load-collection`, but usable from
  within a mutation.

  To use this function make sure your mutation specifies a return value with a remote. The remote
  should include the abstract mutation `(app/load)` as well as any desired `(tx/fallback)`:

  { :remote (om/query->ast '[(app/load)])
    :action (fn []
       (load-data-action ...)
       ; other optimistic updates/state changes)}"
  [app-state query & {:keys [ident without params post-mutation]}]
  (impl/mark-ready
    :state app-state
    :ident ident
    :query query
    :params params
    :without without
    :post-mutation post-mutation))

;; Predicate functions
(defn data-state? [state] (impl/data-state? state))
(defn ready? [state] (impl/ready? state))
(defn loading? [state] (impl/loading? state))
(defn failed? [state] (impl/failed? state))

(defn lazily-loaded
  "Custom rendering for use while data is being lazily loaded using the data fetch methods
  load-collection and load-field.

  `data-render` : the render method to call once the data has been successfully loaded from
  the server. Can be an Om factory method or a React rendering function.

  `props` : the React properties for the element to be loaded.

  Optional:

  `ready-render` : the render method to call when the desired data has been marked as ready
  to load, but the server request has not yet been sent.

  `loading-render` : render method once the server request has been sent, and UI is waiting
  on the response

  `failed-render` : render method when the server returns a failure state for the requested data
  ALPHA WARNING: The transfer of read errors to failed data states is not implemented in this alpha version.

  `not-present-render` : called when props is nil (helpful for differentiating between a nil and
  empty response from the server).

  Example Usage:

  ```
  (defui Thing
    static om/IQuery
    (query [this] [{:thing2 (om/get-query Thing2)}])
    Object
    (componentDidMount [this]
       (load-field this :thing2))

    (render [this]
      (let [thing2 (:thing2 (om/props this))]
        (lazily-loaded Thing2 thing2))))

  (defui Thing2
    static om/IQuery
    (query [this] [:ui/fetch-state])
    Object
    (render [this]
      (display-thing-2))
  ```"
  [data-render props & {:keys [ready-render loading-render failed-render not-present-render]
                        :or   {loading-render (fn [_] (dom/div #js {:className "lazy-loading-load"} "Loading..."))
                               ready-render   (fn [_] (dom/div #js {:className "lazy-loading-ready"} nil))
                               failed-render  (fn [_] (dom/div #js {:className "lazy-loading-failed"} nil))}}]

  (let [state (:ui/fetch-state props)]
    (cond
      (ready? state) (ready-render props)
      (loading? state) (loading-render props)
      (failed? state) (failed-render props)
      (and not-present-render (nil? props)) (not-present-render props)
      :else (data-render props))))
