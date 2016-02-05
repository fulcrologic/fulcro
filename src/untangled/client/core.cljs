(ns untangled.client.core
  (:require
    [om.next :as om]
    [untangled.client.impl.application :as app]
    [untangled.client.impl.network :as net]))

(declare map->Application)

(defn new-untangled-client
  "Entrypoint for creating a new untangled client. Instantiates an Application defrecord with default values, unless
  overridden by the parameters. If you do not supply a networking object, one will be provided that connects to the
  same server the application was served from, at `/api`"
  [& {:keys [initial-state started-callback networking]
      :or   {initial-state {} started-callback #() networking (net/make-untangled-network "/api")}
      :as   config}]
  (map->Application config))

(defprotocol UntangledApplication
  (mount [this root-component target-dom-id] "Start/replace the webapp on the given DOM ID.")
  (refresh [this] "Refresh the UI (force re-render)"))

(defrecord Application [initial-state started-callback networking queue response-channel reconciler parser mounted?]
  UntangledApplication
  (mount [this root-component dom-id]
    (if mounted?
      (do (refresh this) this)
      (app/initialize this initial-state root-component dom-id)))

  (refresh [this] (when (om/mounted? (om/app-root reconciler))
                    (om/force-root-render! reconciler))))

