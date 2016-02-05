(ns untangled.client.impl.application-spec
  (:require
    [untangled.client.impl.application :as impl])
  (:require-macros
    [cljs.test :refer [is are]]
    [untangled-spec.core :refer [specification behavior assertions provided component when-mocking]]))

(specification "App initialization"
  (behavior "returns untangled client app record with"
    (behavior "a request queue")
    (behavior "a response queue")
    (behavior "a reconciler")
    (behavior "a parser")
    (behavior "a marker that the app was initialized")))

(specification "Changing app locale"
  (behavior "triggers a forced reload of the app"))

(specification "Fallback handler"
  (behavior "logs a warning when a transaction failed and the fallback transaction is being called.")
  (behavior "logs a warning when a transaction failed and there is no fallback transaction to call."))

(specification "Server send"
  (behavior "puts fetch transactions on the network request queue.")
  (behavior "puts non-fetch transactions on the network request queue"))
