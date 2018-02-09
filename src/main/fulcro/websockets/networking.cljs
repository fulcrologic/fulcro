(ns fulcro.websockets.networking
  (:require-macros [cljs.core.async.macros :refer (go go-loop)])
  (:require [cljs.core.async :as async :refer (<! >! put! chan)]
            [cognitect.transit :as ct]
            [taoensso.sente :as sente :refer (cb-success?)] ; <--- Add this
            [taoensso.sente.packers.transit :as sente-transit]
            [fulcro.client.primitives :as prim]
            [fulcro.transit :as t]
            [fulcro.client.network :refer [FulcroNetwork]]
            [fulcro.logging :as log]
            [fulcro.websockets.transit-packer :as tp]))

(defprotocol ChannelSocket
  (install-push-handlers [this app] "Install the push handlers. Must be called in started callback, passed the websocket network object, and the completed app" )
  (reconnect [this] "Reconnect the socket"))

(defonce router_ (atom nil))

(defn stop-router! []
  (when-let [stop-f @router_]
    (stop-f)))

(defn start-router! [ch-recv msg-handler]
  (log/info "Starting websocket router.")
  (stop-router!)
  (reset! router_
    (sente/start-chsk-router!
      ch-recv msg-handler)))

(defmulti message-received
  "Multimethod to handle Sente `event-msg`s"
  :id)

(defmulti push-received
  "Multimethod to handle push events"
  (fn [app msg] (:topic msg)))

(defmethod push-received :default [app msg]
  (log/error (str "Received and unhandled message: " msg)))

(defrecord ChannelClient [ch-recv url init-chan channel-socket send-fn global-error-callback req-params parse-queue completed-app]
  ChannelSocket
  (install-push-handlers [this app]
    (defmethod message-received :default [{:keys [ch-recv send-fn state event id ?data]}]
      (let [command (:command ?data)]
        (log/debug "Message Routed to default handler " command)))

    (defmethod message-received :api/parse [{:keys [?data]}]
      (put! parse-queue ?data))

    (defmethod message-received :api/server-push [{:keys [?data] :as msg}]
      (push-received app ?data))

    (defmethod message-received :chsk/handshake [{:keys [ch-recv send-fn state event id ?data] :as message}] )

    (defmethod message-received :chsk/state [{:keys [ch-recv send-fn state event id ?data] :as message}]
      (when (:ever-opened? @state)
        (put! init-chan true))))
  (reconnect [this]
    (sente/chsk-reconnect! channel-socket))

  FulcroNetwork
  (send [this edn ok err]
    (do
      (go
        (let [{:keys [status body]} (<! parse-queue)]
          ;; We are saying that all we care about at this point is the body.
          (if (= status 200)
            (ok body)
            (do
              (log/error (str "SERVER ERROR CODE: " status))
              (when global-error-callback
                (global-error-callback status body))
              (err body)))
          parse-queue))
      (go
        (<! init-chan)
        (send-fn `[:api/parse ~{:action  :send-message
                                :command :send-om-request
                                :content edn}]))))
  (start [this]
    (log/debug "Remember to install the push handlers!")
    (start-router! ch-recv message-received)
    this))



(defn make-channel-client
  "Creates a client side networking component for use in place of the default fulcro networking component.

  Params:
  - `url` - The url to handle websocket traffic on. (ex. \"\\chsk\")
  - `host` (Optional) - server that is hosting the websocket server
  - `global-error-callback` (Optional) - Analagous to the global error callback in fulcro client.
  - `req-params` (Optional) - Params to be attached to the initial request.
  - `state-callback` (Optional) - Callback that runs when the websocket state of the websocket changes.
      The function takes an old state parameter and a new state parameter (arity 2 function).
      `state-callback` can be either a function, or an atom containing a function.
  - `transit-handlers` (Optional) - Expects a map with `:read` and/or `:write` key containing a map of transit handlers,
  "
  [url & {:keys [global-error-callback host req-params state-callback transit-handlers]}]
  (let [parse-queue    (chan)
        {:keys [chsk
                ch-recv
                send-fn
                state]} (sente/make-channel-socket! url     ; path on server
                          {:packer         (tp/make-packer transit-handlers)
                           :host           host
                           :type           :ws              ; e/o #{:auto :ajax :ws}
                           :params         req-params
                           :wrap-recv-evs? false})
        channel-client (map->ChannelClient {:ch-recv               ch-recv
                                            :url                   url
                                            :init-chan             (async/promise-chan)
                                            :channel-socket        chsk
                                            :send-fn               send-fn
                                            :global-error-callback global-error-callback
                                            :req-params            req-params
                                            :parse-queue           parse-queue})]
    (cond
      (fn? state-callback) (add-watch state ::state-callback (fn [a k o n]
                                                               (state-callback o n)))
      (instance? Atom state-callback) (add-watch state ::state-callback (fn [a k o n]
                                                                          (@state-callback o n))))
    channel-client))
