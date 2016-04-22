(ns untangled.client.core
  (:require
    [om.next :as om]
    [untangled.client.impl.application :as app]
    untangled.client.impl.built-in-mutations                ; DO NOT REMOVE. Ensures built-in mutations load on start
    [untangled.client.impl.network :as net]
    [untangled.client.logging :as log]
    [untangled.dom :as udom])
  (:import goog.Uri))

(declare map->Application)

(defn new-untangled-client
  "Entrypoint for creating a new untangled client. Instantiates an Application with default values, unless
  overridden by the parameters. If you do not supply a networking object, one will be provided that connects to the
  same server the application was served from, at `/api`.

  If you supply a `:request-transform` it must be a function:

  ```
 (fn [edn headers] [edn' headers'])
  ```

  it can replace the outgoing EDN or headers (returning both as a vector). NOTE: Both of these are clojurescript types.
  The edn will be encoded with transit, and the headers will be converted to a js map.

  `:initial-state` is your applications initial state. If it is an atom, it *must* be normalized. Untangled databases
  always have normalization turned on (for server data merging). If it is not an atom, it will be auto-normalized.

  `:started-callback` is an optional function that will receive the intiailized untangled application after it is
  mounted in the DOM, and is useful for triggering initial loads, routing mutations, etc. The Om reconciler is available
  under the `:reconciler` key (and you can access the app state, root node, etc from there.)

  `:network-error-callback` is a function of two arguments, the app state atom and the error, which will be invoked for
  every network error (status code >= 400, or no network found), should you choose to use the built-in networking record.

  There is currently no way to circumvent the encoding of the body into transit. If you want to talk to other endpoints
  via alternate protocols you must currently implement that outside of the framework (e.g. global functions/state).
  "
  [& {:keys [initial-state started-callback networking request-transform network-error-callback]
      :or   {initial-state {} started-callback (constantly nil) network-error-callback (constantly nil)}}]
  (map->Application {:initial-state    initial-state
                     :started-callback started-callback
                     :networking       (or networking (net/make-untangled-network "/api"
                                                        :request-transform request-transform
                                                        :global-error-callback network-error-callback))}))

(defprotocol UntangledApplication
  (mount [this root-component target-dom-id] "Start/replace the webapp on the given DOM ID or DOM Node.")
  (reset-state! [this new-state] "Replace the entire app state with the given (pre-normalized) state.")
  (refresh [this] "Refresh the UI (force re-render). NOTE: You MUST support :key on your root DOM element with the :ui/react-key value from app state for this to work.")
  (history [this] "Return a serialized version of the current history of the application, suitable for network transfer"))

(defrecord Application [initial-state started-callback networking queue response-channel reconciler parser mounted?]
  UntangledApplication
  (mount [this root-component dom-id-or-node]
    (if mounted?
      (do (refresh this) this)
      (app/initialize this initial-state root-component dom-id-or-node)))

  (reset-state! [this new-state] (reset! (om/app-state reconciler) new-state))

  (history [this]
    (let [history-steps (-> reconciler :config :history .-arr)
          history-map (-> reconciler :config :history .-index deref)]
      {:steps   history-steps
       :history (into {} (map (fn [[k v]]
                                [k (assoc v :untangled/meta (meta v))]) history-map))}))

  (refresh [this]
    (log/info "RERENDER: NOTE: If your UI doesn't change, make sure you query for :ui/react-key on your Root and embed that as :key in your top-level DOM element")
    (swap! (om/app-state reconciler) assoc :ui/react-key (udom/unique-key))))

(defn new-untangled-test-client
  "A test client that has no networking. Useful for UI testing with a real Untangled app container."
  [& {:keys [initial-state started-callback]
      :or   {initial-state {} started-callback nil}}]
  (map->Application {:initial-state    initial-state
                     :started-callback started-callback
                     :networking       (net/mock-network)}))

(defn get-url
  "Get the current window location from the browser"
  [] (-> js/window .-location .-href))

(defn uri-params
  "Get the current URI parameters from the browser url or one you supply"
  ([] (uri-params (get-url)))
  ([url]
   (let [query-data (.getQueryData (goog.Uri. url))]
     (into {}
       (for [k (.getKeys query-data)]
         [k (.get query-data k)])))))

(defn get-url-param
  "Get the value of the named parameter from the browser URL (or an explicit one)"
  ([param-name] (get-url-param (get-url) param-name))
  ([url param-name]
   (get (uri-params url) param-name)))
