(ns untangled.websockets.components.channel-wrapper
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async :as async :refer [<! close! go-loop]]
            [taoensso.timbre :as timbre]
            [untangled.server.impl.components.handler :refer [api]]
            [untangled.websockets.components.channel-server :refer [message-received]]
            [untangled.websockets.protocols :refer [ChannelHandler req->authenticated? client-dropped
                                                    get-subscribers subscribe unsubscribe]]))

(defn start-push-router [push-queue send-fn subscription-container]
  (timbre/info "Starting push router")
  (go-loop [{:keys [topic exclusions] :as msg} (<! push-queue)]
    (let [users (get-subscribers subscription-container topic)]
      (doseq [user (remove (set exclusions) users)]
        (send-fn user [:api/server-push msg]))
      (when-let [msg (<! push-queue)]
        (recur msg)))))

(defrecord ChannelWrapper [channel-server handler push-queue subscription-container]
  ChannelHandler
  (req->authenticated? [this req client-tab-uuid]
    (swap! (:clients channel-server) conj client-tab-uuid)
    true)
  (client-dropped [this client-tab-uuid]
    (unsubscribe subscription-container client-tab-uuid)
    (swap! (:clients channel-server) disj client-tab-uuid))

  component/Lifecycle
  (start [component]
    (timbre/info "Socket Wrapper system starting up.")

    (let [{:keys [chsk-send!]}     channel-server
          {:keys [api-parser env]} handler]
      (defmethod message-received :default [message]
        (timbre/error (str "Received message " message ", but no receiver wanted it!")))

      (defmethod message-received :api/parse [{:keys [client-id ?data ring-req uid] :as message}]
        (let [result (api {:transit-params (:content ?data)
                           :parser         api-parser
                           :env            (assoc env
                                             :uid uid
                                             :subscription-container subscription-container)})]
          (chsk-send! uid [:api/parse result])))

      (defmethod message-received :chsk/uidport-open [{:keys [client-id ?data ring-req uid] :as message}]
        (timbre/debug "Port opened by client" (:client-id message) (:uid message))
        (req->authenticated? component ring-req uid))

      (defmethod message-received :chsk/uidport-close [{:keys [client-id ?data ring-req uid] :as message}]
        (timbre/debug "Connection closed" client-id)
        (client-dropped component client-id))

      (defmethod message-received :chsk/ws-ping [{:keys [client-id ?data ring-req uid] :as message}]
        #_(timbre/debug "Ping from client" (:client-id message)))

      (start-push-router push-queue chsk-send! subscription-container)))

  (stop [component]
    (timbre/info "Socket Wrapper system shutting down.")
    (remove-all-methods message-received)
    (close! (:ch-recv channel-server))
    (map->ChannelWrapper {})))

(defn make-channel-wrapper [push-queue]
  (component/using
    (map->ChannelWrapper {:push-queue push-queue})
    [:channel-server :handler :subscription-container]))
