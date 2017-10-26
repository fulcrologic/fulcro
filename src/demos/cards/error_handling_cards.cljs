(ns cards.error-handling-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [recipes.error-handling-client :as client]
    [fulcro.client.cards :refer [fulcro-app]]
    [fulcro.client.dom :as dom]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]))

(dc/defcard-doc
  "# Error Handling

  This is a full-stack example. To start the server, make sure you're running a normal clj repl:

  (run-demo-server)

  Note that all of the examples share the same server, but the server code is isolated for each using
  namespacing of the queries and mutations.

  "
  (dc/mkdn-pprint-source client/Child)
  (dc/mkdn-pprint-source client/Root))

(dc/defcard error-handling
  "# Error Handling

  NOTE: The error handling stuff needs work...
  "
  (fulcro-app client/Root
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
