(ns untangled.websockets.components.channel-wrapper
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async :as async :refer [<! close! go-loop]]
            [taoensso.timbre :as timbre]
            [untangled.server.impl.components.handler :refer [api]]
            [untangled.websockets.components.channel-server :refer [message-received]]
            [untangled.websockets.protocols :refer [ChannelHandler req->authenticated? client-dropped
                                                    get-subscribers subscribe unsubscribe]]))

(defn push-router
  "Takes messages off of a push queue, and sends them to the subcribed clients.
  If `:exclusions` are part of the incoming message, then those users, will be ignored in the push."
  [push-queue send-fn subscription-container]
  (timbre/info "Starting push router")
  (go-loop [{:keys [topic exclusions] :as msg} (<! push-queue)]
    (let [users (get-subscribers subscription-container topic)]
      (doseq [user (remove (set exclusions) users)]
        (send-fn user [:api/server-push (dissoc msg :exclusions)]))
      (when-let [msg (<! push-queue)]
        (recur msg)))))

(defrecord ChannelWrapper [channel-server handler push-queue push-router-fn subscription-container]
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

      (push-router-fn push-queue chsk-send! subscription-container)))

  (stop [component]
    (timbre/info "Socket Wrapper system shutting down.")
    (remove-all-methods message-received)
    (close! (:ch-recv channel-server))
    (map->ChannelWrapper {})))

(defn make-channel-wrapper
  "Create a channel wrapper. Depends on a `channel-server`, `handler`, and `subscription-container`

  Params:
  * `push-queue` - A `core.async/chan` that enqueues messages to be pushed.
  * `push-router-fn` - (Optional) A function of arity 3 that takes the `push-queue`,
    the `send-fn` (provided by `channel-server`), and the `subscription-container`."
  [push-queue & {:keys [push-router-fn]}]
  (component/using
    (map->ChannelWrapper {:push-queue     push-queue
                          :push-router-fn (or push-router-fn push-router)})
    [:channel-server :handler :subscription-container]))
