(ns ^:no-doc com.fulcrologic.fulcro.inspect.inspect-ws
  (:require
    [cljs.core.async :as async :refer [>! <!] :refer-macros [go go-loop]]
    [clojure.pprint :refer [pprint]]
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
    [com.fulcrologic.fulcro.inspect.transit :as inspect.transit]
    [com.fulcrologic.fulcro.networking.transit-packer :as tp]
    [taoensso.encore :as enc]
    [taoensso.sente :as sente]
    [taoensso.timbre :as log]))

(goog-define SERVER_PORT "8237")
(goog-define SERVER_HOST "localhost")

(defonce sente-socket-client (atom nil))

(def backoff-ms #(enc/exp-backoff % {:max 15000}))

(defn start-ws-messaging! []
  (when-not @sente-socket-client
    (reset! sente-socket-client
      (sente/make-channel-socket-client! "/chsk" "no-token-desired"
        {:type           :auto
         :host           (str SERVER_HOST ":" SERVER_PORT)
         :packer         (tp/make-packer {:read  inspect.transit/read-handlers
                                          :write inspect.transit/write-handlers})
         :wrap-recv-evs? false
         :backoff-ms-fn  backoff-ms}))
    (log/debug "Starting websockets")
    (let [{:keys [state send-fn]} @sente-socket-client]
      (go-loop [attempt 1]
        (let [open? (:open? @state)]
          (if open?
            (when-let [[type data] (<! inspect/send-ch)]
              (log/debug "Forwarding to server: type =" type)
              (log/trace "Forwarding to server: data =" data)
              (send-fn [:fulcro.inspect/message {:type type :data data :timestamp (js/Date.)}]))
            (do
              (log/trace (str "Waiting for channel to be ready"))
              (async/<! (async/timeout (backoff-ms attempt)))))
          (recur (if open? 1 (inc attempt))))))
    (let [{:keys [state ch-recv]} @sente-socket-client]
      (go-loop [attempt 1]
        (let [open? (:open? @state)]
          (if open?
            (do (enc/when-let [[event-type message] (:event (<! ch-recv))
                               _ (= :fulcro.inspect/event event-type)
                               {:as msg :keys [type data]} message]
                  (log/debug "Forwarding from electron: type =" type)
                  (log/trace "Forwarding from electron: data =" data)
                  (inspect/handle-devtool-message msg)))
            (do
              (log/trace (str "Waiting for channel to be ready"))
              (async/<! (async/timeout (backoff-ms attempt)))))
          (recur (if open? 1 (inc attempt))))))))

(defn install-ws []
  (when-not @inspect/started?*
    (log/info "Installing Fulcro 3.x Inspect over Websockets targeting port " SERVER_PORT)
    (reset! inspect/started?* true)
    (start-ws-messaging!)))

