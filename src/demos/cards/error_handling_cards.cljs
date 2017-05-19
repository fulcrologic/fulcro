(ns cards.error-handling-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [recipes.error-handling-client :as client]
    [untangled.client.cards :refer [untangled-app]]
    [om.dom :as dom]
    [untangled.client.data-fetch :as df]
    [untangled.client.logging :as log]))

(dc/defcard-doc
  "# Error Handling

  This is a full-stack example. To start the server, make sure you're running a normal clj repl:

  (go)

  Note that all of the examples share the same server, but the server code is isolated for each using
  namespacing of the queries and mutations.

  "
  (dc/mkdn-pprint-source client/Child)
  (dc/mkdn-pprint-source client/Root))

(dc/defcard error-handling
  "# Error Handling

  NOTE: The error handling stuff needs work...
  "
  (untangled-app client/Root
    :started-callback
    (fn [{:keys [reconciler]}]
      ;; specify a fallback mutation symbol as a named parameter after the component or reconciler and query
      (df/load reconciler :data nil {:fallback 'read/error-log}))

    ;; this function is called on *every* network error, regardless of cause
    :network-error-callback
    (fn [state status-code error]
      (log/warn "Global callback:" error " with status code: " status-code)))
  {}
  {:inspect-data true})
