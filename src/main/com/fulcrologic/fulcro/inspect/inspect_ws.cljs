(ns ^:no-doc com.fulcrologic.fulcro.inspect.inspect-ws
  (:require
    [cljs.core.async :as async :refer [>! <!] :refer-macros [go go-loop]]
    [clojure.pprint :refer [pprint]]
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
    [com.fulcrologic.fulcro.inspect.transit :as inspect.transit]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid :refer [TempId]]
    [com.fulcrologic.fulcro.algorithms.transit :as ot]
    [taoensso.sente.packers.transit :as st]
    [taoensso.encore :as enc]
    [taoensso.sente :as sente]
    [taoensso.timbre :as log]))

(defn make-packer
  "Returns a json packer for use with sente."
  [{:keys [read write]}]
  (st/->TransitPacker :json
    {:handlers (cond-> {TempId (ot/->TempIdHandler)}
                 write (merge write))}
    {:handlers (cond-> {tempid/tag (fn [id] (tempid/tempid id))}
                 read (merge read))}))

(goog-define SERVER_PORT "8237")
(goog-define SERVER_HOST "localhost")

(defonce sente-socket-client (atom nil))

(def backoff-ms #(enc/exp-backoff % {:max 1000}))

(defn start-ws-messaging!
  [& [{:keys [channel-type] :or {channel-type :auto}}]]
  (when-not @sente-socket-client
    (reset! sente-socket-client
      (sente/make-channel-socket-client! "/chsk" "no-token-desired"
        {:type           channel-type
         :host           SERVER_HOST
         :port           SERVER_PORT
         :protocol       :http
         :packer         (make-packer {:read  inspect.transit/read-handlers
                                       :write inspect.transit/write-handlers})
         :wrap-recv-evs? false
         :backoff-ms-fn  backoff-ms}))
    (log/debug "Starting websockets at:" SERVER_HOST ":" SERVER_PORT)
    (go-loop [attempt 1]
      (if-not @sente-socket-client
        (log/info "Shutting down inspect ws async loops.")
        (let [{:keys [state send-fn]} @sente-socket-client
              open? (:open? @state)]
          (if open?
            (when-let [[type data] (<! inspect/send-ch)]
              (log/debug "Forwarding to server: type =" type)
              (log/trace "Forwarding to server: data =" data)
              (send-fn [:fulcro.inspect/message {:type type :data data :timestamp (js/Date.)}]))
            (do
              (log/trace (str "Waiting for channel to be ready"))
              (async/<! (async/timeout (backoff-ms attempt)))))
          (recur (if open? 1 (inc attempt))))))
    (go-loop [attempt 1]
      (if-not @sente-socket-client
        (log/info "Shutting down inspect ws async loops.")
        (let [{:keys [state ch-recv]} @sente-socket-client
              open? (:open? @state)]
          (if open?
            (enc/when-let [[event-type message] (:event (<! ch-recv))
                           _ (= :fulcro.inspect/event event-type)
                           {:as msg :keys [type data]} message]
              (log/debug "Forwarding from electron: type =" type)
              (log/trace "Forwarding from electron: data =" data)
              (inspect/handle-devtool-message msg))
            (do
              (log/trace (str "Waiting for channel to be ready"))
              (async/<! (async/timeout (backoff-ms attempt)))))
          (recur (if open? 1 (inc attempt))))))))

(defn install-ws []
  (when-not @inspect/started?*
    (log/info "Installing Fulcro 3.x Inspect over Websockets targeting port " SERVER_PORT)
    (reset! inspect/started?* true)
    (start-ws-messaging!)))

(defn stop-ws []
  (log/info "Shutting down inspect websockets.")
  (reset! sente-socket-client nil)
  (reset! inspect/started?* false))
