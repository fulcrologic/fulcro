(ns ^:no-doc com.fulcrologic.fulcro.inspect.inspect-ws
  (:require
    [cljs.core.async :as async :refer [>! <!] :refer-macros [go go-loop]]
    [goog.object :as gobj]
    [clojure.pprint :refer [pprint]]
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
    [com.fulcrologic.fulcro.inspect.transit :as inspect.transit]
    [taoensso.timbre :as log]
    [taoensso.sente :as sente]
    [com.fulcrologic.fulcro.networking.transit-packer :as tp]))

(goog-define SERVER_PORT "8237")
(goog-define SERVER_HOST "localhost")

(defonce sente-socket-client (atom nil))

(defn start-ws-messaging! []
  (when-not @sente-socket-client
    (reset! sente-socket-client
      (let [{:keys [ch-recv state send-fn] :as result}
            (sente/make-channel-socket-client! "/chsk" "no-token-desired"
              {:type   :auto
               :host   (str SERVER_HOST ":" SERVER_PORT)
               :packer (tp/make-packer {:read inspect.transit/read-handlers
                                        :write inspect.transit/write-handlers})})]
        (log/debug "Starting websockets")
        (go-loop []
          (if (-> state deref :open?)
            (when-let [[type data :as msg] (<! inspect/send-ch)]
              (log/info "Inspect wants to send" (with-out-str (pprint msg)))
              (send-fn [:inspect/message {:type type :data data :timestamp (js/Date.)}] 10000))
            (do
              (log/info "Send attempted before channel ready...waiting")
              (async/<! (async/timeout 1000))))
          (recur))
        (go-loop []
          (if (-> state deref :open?)
            (when-some [{:keys [event]} (<! ch-recv)]
              (log/debug "Incoming event from electron:" (with-out-str (pprint event)))
              (let [[_ [_ msg]] event]
                (log/info "Incoming message from electron:" (with-out-str (pprint msg)))
                (inspect/handle-devtool-message msg)))
            (do
              (log/info "Send attempted before channel ready...waiting")
              (async/<! (async/timeout 1000))))
          (recur))
        result))))

(defn install-ws []
  (when-not @inspect/started?*
    (log/info "Installing Fulcro 3.x Inspect over Websockets targeting port " SERVER_PORT)
    (reset! inspect/started?* true)
    (start-ws-messaging!)))

