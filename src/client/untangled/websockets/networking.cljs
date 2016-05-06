(ns untangled.websockets.networking
  (:require-macros [cljs.core.async.macros :refer (go go-loop)])
  (:require [cljs.core.async :as async :refer (<! >! put! chan)]
            [cognitect.transit :as ct]
            [taoensso.sente :as sente :refer (cb-success?)] ; <--- Add this
            [taoensso.sente.packers.transit :as sente-transit]
            [om.next :as om]
            [om.transit :as t]
            [untangled.client.impl.network :refer [UntangledNetwork]]
            [untangled.client.logging :as log]
            [untangled.transit-packer :as tp]))

(defonce router_ (atom nil))

(defn stop-router! []
  (when-let [stop-f @router_]
    (stop-f)))

(defn start-router! [ch-recv msg-handler]
  (js/console.log "Starting websocket router.")
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

(defrecord ChannelClient [url send-fn callback global-error-callback server-push parse-queue completed-app]
  UntangledNetwork
  (send [this edn ok err]
    (do
      (callback ok err)
      (send-fn `[:api/parse ~{:action  :send-message
                              :command :send-om-request
                              :content edn}])))
  (start [this app]
    (let [this (assoc this :completed-app app)]
      (defmethod message-received :default [{:keys [ch-recv send-fn state event id ?data]}]
        (let [command (:command ?data)]
          (log/error "Message Routed to default handler " command)))

      (defmethod message-received :api/parse [{:keys [?data]}]
        (put! parse-queue ?data))

      (defmethod message-received :api/server-push [{:keys [?data] :as msg}]
        (let [app (:completed-app this)]
          (push-received app ?data)))

      (defmethod message-received :chsk/handshake [message]
        (log/debug "Message Routed to handshake handler "))

      (defmethod message-received :chsk/state [message]
        (log/debug "Message Routed to state handler")))))

(defn make-channel-client [url & {:keys [global-error-callback]}]
  (let [parse-queue     (chan)
        {:keys [chsk
                ch-recv
                send-fn
                state]} (sente/make-channel-socket! url ; path on server
                          {:packer         tp/packer
                           :type           :ws ; e/o #{:auto :ajax :ws}
                           :wrap-recv-evs? false})
        channel-client  (map->ChannelClient {:url                   url
                                             :send-fn               send-fn
                                             :global-error-callback (atom global-error-callback)
                                             :parse-queue           parse-queue
                                             :callback              (fn [valid error]
                                                                      (go
                                                                        (let [{:keys [status body]} (<! parse-queue)]
                                                                          ;; We are saying that all we care about at this point is the body.
                                                                          (if (= status 200)
                                                                            (valid body)
                                                                            (error body))
                                                                          parse-queue)))})]
    (start-router! ch-recv message-received)
    channel-client))
