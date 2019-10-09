(ns com.fulcrologic.fulcro.inspect.inspect-ws
  (:require
    ["socket.io-client" :as io-client]
    [cljs.core.async :as async :refer [go]]
    [goog.object :as gobj]
    [com.fulcrologic.fulcro.inspect.transit :as encode]
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
    [taoensso.timbre :as log]))

(goog-define SERVER_PORT "8237")

(defprotocol WS
  (start [this])
  (stop [this])
  (push [this message]))

(defrecord Websockets [ws-url ws-in ws-out message-received]
  WS
  (start [this]
    (log/debug "Starting websockets")
    (let [ws (io-client ws-url)]
      (.on ws "connect"
        (fn []
          (log/debug "Client Connected")
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
          (log/debug "Disconnected")
          (message-received {:type :close})
          (stop this)
          (log/warn "WS-CLOSE" e)))

      (.on ws "event"
        (fn [e]
          (log/debug "event" e)
          (when-let [msg (some-> e (gobj/get "fulcro-inspect-devtool-message") encode/read)]
            (go (async/>! ws-in msg)))))
      :ok))
  (push [this message] (go (async/>! ws-out message)))
  (stop [this]
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

(defn start-ws-messaging! []
  (try
    (let [socket (websockets (str "http://localhost:" SERVER_PORT) (fn [msg]
                                                                     (inspect/handle-devtool-message msg)))]
      (start socket)
      (async/go-loop []
        (when-let [[type data] (async/<! inspect/send-ch)]
          (push socket {:type type :data data :timestamp (js/Date.)})
          (recur))))
    (catch :default e
      (log/error e "Unable to start inspect."))))

(defn install-ws []
  (when-not @inspect/started?*
    (log/info "Installing Fulcro 3.x Inspect over Websockets targeting port " SERVER_PORT)
    (reset! inspect/started?* true)
    (start-ws-messaging!)))
