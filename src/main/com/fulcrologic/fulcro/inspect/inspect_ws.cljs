(ns ^:no-doc com.fulcrologic.fulcro.inspect.inspect-ws
  (:require
    [cljs.core.async :as async :refer [>! <!] :refer-macros [go go-loop]]
    [goog.object :as gobj]
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
    [taoensso.timbre :as log]
    [taoensso.sente :as sente]
    [com.fulcrologic.fulcro.networking.transit-packer :as tp]))

(goog-define SERVER_PORT "8237")
(goog-define SERVER_HOST "localhost")

(defprotocol WS
  (start [this])
  (stop [this])
  (push [this message]))

(declare restart-ws)

(defn- connect-client! [{:keys [message-processor sente-env]}]
  (let [{:keys [ch-recv]} sente-env]
    (log/debug "Client Connected")
    (go-loop []
      (when-some [msg (<! ch-recv)]
        (message-processor (:data msg))
        (recur)))))

(defn- disconnect-client! [{:as this :keys [message-processor]}]
  (log/debug "Disconnected")
  (message-processor {:type :close})
  (stop this)
  (log/warn "WS-CLOSE")
  (restart-ws))

(defn- handle-inspect-message [{:keys [message-processor]} {:as msg :keys [data]}]
  (log/debug "event" msg)
  (some-> data
    :fulcro-inspect-devtool-message
    message-processor))

(defn- forward-client-message-to-server [{:keys [sente-env]} msg]
  (let [{:keys [send-fn]} sente-env]
    (send-fn [:fulcro.inspect/event
              {:uuid    (str (-> msg :data :fulcro.inspect.core/app-uuid))
               :message msg}])))

(defrecord Websockets [sente-env message-processor]
  WS
  (start [this]
    (log/debug "Starting websockets")
    (let [{:keys [ch-recv state]} sente-env]
      (add-watch state ::connections
        (fn [_ _ old new]
          (cond
            (and (:open? new) (not (:open? old)))
            #_=> (connect-client! this)
            (and (:open? old) (not (:open? new)))
            #_=> (disconnect-client! this)
            :else :noop)))
      (go-loop []
        (when-some [msg (<! ch-recv)]
          (handle-inspect-message this msg))
        (recur)))
    :ok)
  (push [this message] (forward-client-message-to-server this message))
  (stop [this]
    ;;TODO
    :WIP))

(defonce
  ^{:private true :doc "{:keys [chsk ch-recv send-fn state]}"}
  sente-socket-client
  (sente/make-channel-socket-client! "/chsk"
    {:type   :auto
     :host   SERVER_HOST
     :port   SERVER_PORT
     :packer (tp/make-packer {})}))

(defn websockets
  "Create a websockets object that. Call `ws/start` on it to connect. Incoming
  messages will be sent to `message-processor`, and outgoing messages can be sent
  with `ws/push`."
  [message-processor]
  (map->Websockets
    {:sente-env         sente-socket-client
     :message-processor message-processor}))

(defn start-ws-messaging! []
  (try
    (let [ws (websockets inspect/handle-devtool-message)]
      (start ws)
      (go-loop []
        (when-let [[type data] (<! inspect/send-ch)]
          (push ws {:type type :data data :timestamp (js/Date.)})
          (recur))))
    (catch :default e
      (log/error e "Unable to start inspect."))))

(defn install-ws []
  (when-not @inspect/started?*
    (log/info "Installing Fulcro 3.x Inspect over Websockets targeting port " SERVER_PORT)
    (reset! inspect/started?* true)
    (start-ws-messaging!)))

(defn restart-ws []
  (log/info "Restarting Fulcro 3.x Inspect over Websockets targeting port " SERVER_PORT)
  (start-ws-messaging!))
