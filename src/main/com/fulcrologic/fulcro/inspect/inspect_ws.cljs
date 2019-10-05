(ns com.fulcrologic.fulcro.inspect.inspect-ws
  (:require
    ["socket.io-client" :as io-client]
    [cljs.core.async :as async :refer [go]]
    [goog.object :as gobj]
    [com.fulcrologic.fulcro.inspect.transit :as encode]))

(defprotocol WS
  (start [this])
  (stop [this])
  (push [this message]))

(defrecord Websockets [ws-url ws-in ws-out message-received]
  WS
  (start [this]
    (js/console.log "Starting websockets")
    (let [ws (io-client ws-url)]
      (.on ws "connect"
        (fn []
          (js/console.log "Client Connected")
          (go (loop []
                (when-some [msg (<! ws-out)]
                  (.emit ws "event" (encode/write msg))
                  (recur)))

            (async/close! ws-in)
            (.close ws))

          (go (loop []
                (when-some [msg (<! ws-in)]
                  (message-received msg)
                  (recur)))

            (async/close! ws-out)
            (.close ws))))

      (.on ws "disconnect"
        (fn [e]
          (js/console.log "Disconnected")
          (message-received {:type :close})
          (stop this)
          (js/console.warn "WS-CLOSE" e)))

      (.on ws "event"
        (fn [e]
          (js/console.log e)
          (when-let [msg (some-> e (gobj/get "fulcro-inspect-devtool-message") encode/read)]
            (go (async/>! ws-in msg)))))
      :ok))
  (push [this message] (go (async/>! ws-out message)))
  (stop [this]
    (js/console.log "Stopping websockets")
    (async/close! ws-in)
    (async/close! ws-out)))

(defn websockets
  "Create a websockets object that. Call `ws/start` on it to connect. Incoming
  messages will be sent to `message-processor`, and outgoing messages can be sent
  with `ws/push`."
  [url message-processor]
  (map->Websockets {:ws-url           url
                    :ws-in            (async/chan 10)
                    :ws-out           (async/chan 10)
                    :message-received message-processor}))
