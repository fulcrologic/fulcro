(ns untangled.websockets.components.channel-wrapper
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async :as async]
            [taoensso.timbre :as timbre]
            [untangled.server.impl.components.handler :refer [api]]
            [untangled.websockets.components.channel-server :refer [message-received]]
            [untangled.websockets.protocols :refer [ChannelHandler req->authenticated? client-dropped]]))

(defrecord ChannelWrapper [channel-server handler subscriptions]
  ChannelHandler
  (req->authenticated? [this req client-tab-uuid]
    (swap! (:clients channel-server) conj client-tab-uuid)
    true)
  (client-dropped [this client-tab-uuid]
    (swap! (:clients channel-server) disj client-tab-uuid))

  component/Lifecycle
  (start [component]
    (timbre/info "Socket Wrapper system starting up.")

    (let [{:keys [chsk-send!]} channel-server
          {:keys [api-parser env]} handler]
      (defmethod message-received :default [message]
       (timbre/error (str "Received message " message ", but no receiver wanted it!")))

     (defmethod message-received [:api/parse :send-message] [{:keys [client-id ?data ring-req uid] :as message}]
       (timbre/debug "Received and api call: " ?data)
       (let [result (api {:transit-params (:content ?data)
                          :parser         api-parser
                          :env            env})]
         (timbre/debug "Api result: " result)
         (chsk-send! uid [:api/parse result])))

     (defmethod message-received [:chsk/uidport-open :default] [{:keys [client-id ?data ring-req uid] :as message}]
       (timbre/debug "Port opened by client" (:client-id message) (:uid message))
       (req->authenticated? component ring-req uid))

     (defmethod message-received [:chsk/uidport-close :default] [{:keys [client-id ?data ring-req uid] :as message}]
       (timbre/debug "Connection closed" client-id)
       (client-dropped component client-id))

     (defmethod message-received [:chsk/ws-ping :default] [{:keys [client-id ?data ring-req uid] :as message}]
       #_(timbre/debug "Ping from client" (:client-id message)))))

  (stop [component]
    (timbre/info "Socket Wrapper system shutting down.")
    (assoc component :subscriptions (atom {}))
    (remove-all-methods message-received)
    (async/close! (:ch-recv channel-server))
    (map->ChannelWrapper {})))

(defn make-channel-wrapper []
   (component/using
     (map->ChannelWrapper {:subscriptions (atom {})})
     [:channel-server :handler]))
