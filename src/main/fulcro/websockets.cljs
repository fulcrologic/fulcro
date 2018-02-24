(ns fulcro.websockets
  (:require-macros [cljs.core.async.macros :refer (go go-loop)])
  (:require [cognitect.transit :as ct]
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
      (log/debug "Unsupported message " id))))

(defrecord Websockets [channel-socket push-handler websockets-uri host state-callback global-error-callback transit-handlers req-params stop app auto-retry?]
  FulcroNetwork
  (send [this edn ok err]
    (let [{:keys [send-fn]} @channel-socket]
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
                ; possibly useful to handle these separately
                #_(case resp
                    :chsk/closed (println "Connection closed...")
                    :chsk/error (println "Connection error...")
                    :chsk/timeout (println "Connection timeout...")
                    (println "Unknown resp: " resp))
                ; retry...probably don't need a back-off, but YMMV
                (js/setTimeout #(fulcro.client.network/send this edn ok err) 1000))
              (let [body {:fulcro.server/error :network-disconnect}]
                (err body)
                (global-error-callback {:status 408 :body body}))))))))
  (start [this]
    (let [{:keys [ch-recv state] :as cs} (sente/make-channel-socket! websockets-uri ; path on server
                                           {:packer         (tp/make-packer transit-handlers)
                                            :host           host
                                            :type           :ws ; e/o #{:auto :ajax :ws}
                                            :params         req-params
                                            :wrap-recv-evs? false})
          message-received (make-event-handler push-handler)]
      (cond
        (fn? state-callback) (add-watch state ::state-callback (fn [a k o n]
                                                                 (state-callback o n)))
        (instance? Atom state-callback) (add-watch state ::state-callback (fn [a k o n]
                                                                            (@state-callback o n))))
      (reset! channel-socket cs)
      (sente/start-chsk-router! ch-recv message-received)
      this)))

(defn make-websocket-networking
  "Creates a websocket-based networking component for use as a Fulcro remote.

  ALPHA QUALITY: Feel free to copy the source into your project and expand it as needed.

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
  "
  [& {:keys [websockets-uri global-error-callback push-handler host req-params state-callback transit-handlers auto-retry?]}]
  (map->Websockets {:channel-socket        (atom nil)
                    :auto-retry?           auto-retry?
                    :websockets-uri        (or websockets-uri "/chsk")
                    :push-handler          push-handler
                    :host                  host
                    :state-callback        state-callback
                    :global-error-callback global-error-callback
                    :transit-handlers      transit-handlers
                    :app                   (atom nil)
                    :stop                  (atom nil)
                    :req-params            req-params}))
