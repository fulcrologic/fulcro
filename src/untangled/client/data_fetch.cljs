(ns untangled.client.data-fetch
  (:require
    [clojure.walk :refer [walk prewalk]]
    [om.next :as om]
    [untangled.client.impl.data-fetch :as impl]
    [om.next.impl.parser :as op]
    [om.dom :as dom]))

(defn load-field
  "Load a field of the current component.

  Parameters
  - `component`: The component
  - `field`: A field on the current component's query that you wish to load
  - `without`: Named parameter for excluding child keys from the query (e.g. for recursive queries or additional laziness)
  - `params`: Named parameter for adding params to the query sent to the server for this field.
  "
  [component field & {:keys [without params callback]}]
  (om/transact! component [(list 'app/load
                             {:ident    (om/get-ident component)
                              :field    field
                              :query    (om/focus-query (om/get-query component) [field])
                              :params   params
                              :without  without
                              :callback callback})]))

(defn load-collection
  "Load a collection from the remote.

  Parameters
  - `comp-or-reconciler`: A component or reconciler (not a class)
  - `query`: The query for the element(s) attributes. Use defui to generate arbitrary queries so normalization will work.
  - Named parameter `ident`: An ident, used if loading a singleton and you wish to specify 'which one'.

  Named parameters `:without` and `:params` are as in `load-field`.
  "
  [comp-or-reconciler query & {:keys [ident without params callback]}]
  (let []
    (om/transact! comp-or-reconciler [(list 'app/load
                                        {:ident    ident
                                         :query    query
                                         :params   params
                                         :without  without
                                         :callback callback})])))

(def load-singleton load-collection)


(defn mark-loading
  "Marks all of the items in the ready-to-load state as loading, places the loading markers in the appropriate locations
  in the app state, and returns a map with the keys:

  `query` : The full query to send to the server.
  `on-load` : The function to call to merge a response. Detects missing data and sets failure markers for those.
  `on-error` : The function to call to set network/server error(s) in place of loading markers.

  response-channel will have the response posted to it when the request is done.
  ."
  [reconciler]
  (impl/mark-loading reconciler))

;; Predicate functions
(defn data-state? [state] (impl/data-state? state))
(defn ready? [state] (impl/ready? state))
(defn loading? [state] (impl/loading? state))
(defn failed? [state] (impl/failed? state))

(defn lazily-loaded
  "Wraps a react element in a renderer that can show lazy loading status.

  Example:

  ```
  (defui Thing
    Object
    (render [this]
      (lazily-loaded QuestionToolbox this)))
  ```
  "
  [element props]
  (let [state (:ui/fetch-state props)]
    (cond
      (ready? state) (dom/span #js {:className "lazy-loading ready"} "")
      (loading? state) (dom/span #js {:className "lazy-loading loading"} "Loading...")
      (failed? state) (dom/span #js {:className "lazy-loading failed"} "FAILED!")
      :else (element props) )))
