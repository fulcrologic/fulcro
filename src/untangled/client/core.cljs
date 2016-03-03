(ns untangled.client.core
  (:require
    [om.next :as om]
    [untangled.client.impl.application :as app]
    [untangled.client.impl.network :as net]
    [untangled.client.logging :as log]
    [untangled.dom :as udom]))

(declare map->Application)

(defn new-untangled-client
  "Entrypoint for creating a new untangled client. Instantiates an Application defrecord with default values, unless
  overridden by the parameters. If you do not supply a networking object, one will be provided that connects to the
  same server the application was served from, at `/api`"
  [& {:keys [initial-state started-callback networking]
      :or   {initial-state {} started-callback nil networking (net/make-untangled-network "/api")}}]
  (map->Application {:initial-state    initial-state
                     :started-callback started-callback
                     :networking       networking}))

(defprotocol UntangledApplication
  (mount [this root-component target-dom-id] "Start/replace the webapp on the given DOM ID or DOM Node.")
  (reset-state! [this new-state] "Replace the entire app state with the given (pre-normalized) state.")
  (refresh [this] "Refresh the UI (force re-render)"))

(defrecord Application [initial-state started-callback networking queue response-channel reconciler parser mounted?]
  UntangledApplication
  (mount [this root-component dom-id-or-node]
    (if mounted?
      (do (refresh this) this)
      (app/initialize this initial-state root-component dom-id-or-node)))

  (reset-state! [this new-state] (reset! (om/app-state reconciler) new-state))

  (refresh [this]
    (log/info "RERENDER: NOTE: If your UI doesn't change, make sure you query for :react-key on your Root and embed that as :key in your top-level DOM element")
    (swap! (om/app-state reconciler) assoc :react-key (udom/unique-key))))

(defn new-untangled-test-client
  "A test client that has no networking. Useful for UI testing with a real Untangled app container."
  [& {:keys [initial-state started-callback]
      :or   {initial-state {} started-callback nil}}]
  (map->Application {:initial-state    initial-state
                     :started-callback started-callback
                     :networking       (net/mock-network)}))


