(ns untangled.websockets.networking
  (:require-macros [cljs.core.async.macros :refer (go go-loop)])
  (:require [cljs.core.async :as async :refer (<! >! put! chan)]
            [cognitect.transit :as ct]
            [taoensso.sente :as sente :refer (cb-success?)] ; <--- Add this
            [taoensso.sente.packers.transit :as sente-transit]
            [om.next :as om]
            [om.transit :as t]
            [untangled.client.impl.network :refer [UntangledNetwork]]
            [untangled.transit-packer :as tp]))

(defrecord ChannelClient [url send-fn callback global-error-callback server-push]
  UntangledNetwork
  (send [this edn ok err]
    (do
      (@callback ok err)
      (send-fn `[:api/parse ~{:action  :send-message
                              :command :send-om-request
                              :content edn}]))))

(defmulti message-received
  "Multimethod to handle Sente `event-msg`s"
  :id)

(defn make-channel-client [url & {:keys [global-error-callback push-queue]}]
  (let [parse-queue                          (chan)
        {:keys [chsk ch-recv send-fn state]} (sente/make-channel-socket! url ; path on server
                                               {:packer         tp/packer
                                                :type           :ws ; e/o #{:auto :ajax :ws}
                                                :wrap-recv-evs? false})]
    (def chsk chsk)
    (def ch-chsk ch-recv)                                     ; ChannelSocket's receive channel
    (def chsk-send! send-fn)                                  ; ChannelSocket's send API fn
    (def chsk-state state)                                    ; Watchable, read-only atom)
    (defonce router_ (atom nil))

    (defn stop-router! []
      (when-let [stop-f @router_]
        (stop-f)))

    (defn start-router! []
      (js/console.log "Starting websocket router.")
      (stop-router!)
      (reset! router_
        (sente/start-chsk-router!
          ch-chsk message-received)))

    (start-router!)

    (defmethod message-received :default [{:keys [ch-recv send-fn state event id ?data]}]
      (let [command (:command ?data)]
        (println "Message Routed to default handler " command)))

    (defmethod message-received :api/parse [{:keys [?data]}]
      (put! parse-queue ?data))

    (defmethod message-received :api/server-push [{:keys [?data] :as msg}]
      (println "Received a server push with:")
      (js/console.log msg)
      (put! push-queue ?data))

    (defmethod message-received :chsk/handshake [message]
      (println "Message Routed to handshake handler "))

    (defmethod message-received :chsk/state [message]
      (println "Message Routed to state handler"))

    (map->ChannelClient {:url                   url
                         :send-fn               chsk-send!
                         :global-error-callback (atom global-error-callback)
                         :server-push           {:push-queue push-queue}
                         :callback              (atom (fn [valid error]
                                                        (go
                                                          (let [{:keys [status body]} (<! parse-queue)]
                                                            ;; We are saying that all we care about at this point is the body.
                                                            (if (= status 200)
                                                              (valid body)
                                                              (error body))
                                                            parse-queue))))})))
