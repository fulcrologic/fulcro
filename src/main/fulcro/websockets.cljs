(ns fulcro.websockets
  (:require-macros [cljs.core.async.macros :refer (go go-loop)])
  (:require [cognitect.transit :as ct]
            [cljs.core.async :as async]
            [taoensso.sente :as sente :refer (cb-success?)]
            [fulcro.client.network :refer [FulcroNetwork]]
            [fulcro.logging :as log]
            [fulcro.websockets.transit-packer :as tp]))

(defn- make-event-handler
  "Probably need to make it possible for extension from outside."
  [push-handler]
  (fn [{:keys [id ?data] :as event}]
    (case id
      :api/server-push (when push-handler (push-handler ?data))
      nil)))

(defrecord Websockets [queue ready? channel-socket push-handler
                       websockets-uri host state-callback
                       global-error-callback transit-handlers
                       req-params stop app auto-retry? sente-options
                       csrf-token]
  FulcroNetwork
  (send [this edn ok err] (async/go (async/>! queue {:this this :edn edn :ok ok :err err})))
  (start [this]
    (let [{:keys [ch-recv state] :as cs} (sente/make-channel-socket-client!
                                           websockets-uri   ; path on server
                                           csrf-token
                                           (merge {:packer         (tp/make-packer transit-handlers)
                                                   :host           host
                                                   :type           :auto ; e/o #{:auto :ajax :ws}
                                                   :backoff-ms-fn  (fn [attempt] (min (* attempt 1000) 4000))
                                                   :params         req-params
                                                   :wrap-recv-evs? false}
                                             sente-options))
          message-received (make-event-handler push-handler)]
      (add-watch state ::ready (fn [a k o n]
                                 (if auto-retry?
                                   (do (reset! ready? (:open? n))) ; prevent send attempts until open again.
                                   (when (:open? n)         ; not auto-retry: so single-shot. offline down should result in app level network error
                                     (reset! ready? true)))))
      (cond
        (fn? state-callback) (add-watch state ::state-callback (fn [a k o n] (state-callback o n)))
        (instance? Atom state-callback) (add-watch state ::state-callback (fn [a k o n] (@state-callback o n))))
      (reset! channel-socket cs)
      (sente/start-chsk-router! ch-recv message-received)

      (async/go-loop []
        (if @ready?
          (let [{:keys [this edn ok err]} (async/<! queue)
                {:keys [send-fn]} @channel-socket]
            (try
              (send-fn [:fulcro.client/API edn] 30000
                (fn process-response [resp]
                  (if (cb-success? resp)
                    (let [{:keys [status body]} resp]
                      (if (= 200 status)
                        (ok body)
                        (do
                          (err body)
                          (when global-error-callback
                            (global-error-callback resp)))))
                    (if auto-retry?
                      (do
                        ; retry...sente already does connection back-off, so probably don't need back-off here
                        (js/setTimeout #(fulcro.client.network/send this edn ok err) 1000))
                      (let [body {:fulcro.server/error :network-disconnect}]
                        (err body)
                        (when global-error-callback
                          (global-error-callback {:status 408 :body body})))))))
              (catch :default e
                (log/error "Sente send failure!" e))))
          (do
            (log/info "Send attempted before channel ready...waiting")
            (async/<! (async/timeout 1000))))
        (recur))
      this)))

(defn make-websocket-networking
  "Creates a websocket-based networking component for use as a Fulcro remote.

  Params:
  - `websockets-uri` - The uri to handle websocket traffic on. (ex. \"/chsk\", which is the default value)
  - `push-handler` - A function (fn [{:keys [topic msg]}] ...) that can handle a push message.
                     The topic is the server push verb, and the message will be the EDN sent.
  - `host` - Host option to send to sente
  - `req-params` - Params for sente socket creation
  - `transit-handlers` - A map with optional :read and :write keys that given added sente packer.
  - `state-callback` (Optional) - Callback that runs when the websocket state of the websocket changes.
                                  The function takes an old state parameter and a new state parameter (arity 2 function).
                                  `state-callback` can be either a function, or an atom containing a function.
  - `global-error-callback` - A function (fn [resp] ...) that is called when returned status code from the server is not 200.
  - `auto-retry?` - A boolean (default false). If set to true any network disconnects will lead to infinite retries until
                    the network returns. All remote mutations should be idempotent.
  - `sente-options` - A map of options that is passed directly to the sente websocket channel construction (see sente docs).
  - `csrf-token` - The CSRF token provided by the server (embedded in HTML. See Dev Guide). If
  not supplied, it looks for the value in `js/fulcro_network_csrf_token` as a global js var.
  "
  ([] (make-websocket-networking {}))
  ([{:keys [websockets-uri global-error-callback push-handler host req-params
            state-callback transit-handlers auto-retry? sente-options
            csrf-token]}]
   (let [csrf-token (or csrf-token js/fulcro_network_csrf_token "NO CSRF TOKEN SUPPLIED.")]
     (map->Websockets {:channel-socket        (atom nil)
                      :csrf-token            csrf-token
                      :queue                 (async/chan)
                      :ready?                (atom false)
                      :auto-retry?           auto-retry?
                      :websockets-uri        (or websockets-uri "/chsk")
                      :push-handler          push-handler
                      :host                  host
                      :state-callback        state-callback
                      :global-error-callback global-error-callback
                      :transit-handlers      transit-handlers
                      :app                   (atom nil)
                      :stop                  (atom nil)
                      :req-params            req-params
                      :sente-options         sente-options}))))
