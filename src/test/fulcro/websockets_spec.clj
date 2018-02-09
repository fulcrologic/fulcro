(ns fulcro.websockets-spec
  (:require [fulcro-spec.core :refer [specification behavior provided assertions when-mocking]]
            [fulcro.websockets.protocols :as wsp]
            [fulcro.websockets :as ws]
            [fulcro.server :as server])
  (:import (fulcro.websockets.protocols WSListener)))

(specification "sente-event-handler" :focused
  (let [parser-calls    (atom 0)
        parser          (fn [& args] (swap! parser-calls inc))
        adds            (atom #{})
        drops           (atom #{})
        reply           (atom nil)
        send-fn         :mock-send
        reply-fn        (fn [result] (reset! reply result))
        listener        (reify
                          WSListener
                          (client-added [this ws cid] (swap! adds conj cid))
                          (client-dropped [this ws cid] (swap! drops conj cid)))
        mock-websockets {:send-fn send-fn :listeners (atom #{listener}) :parser parser :database :mockdb}

        event           (fn
                          ([i uid] {:?reply-fn reply-fn :id i :uid uid})
                          ([i uid query] {:?reply-fn reply-fn :id i :uid uid :?data query}))]

    (when-mocking
      (server/handle-api-request p env q) => (do
                                               (behavior "API Requests"
                                                 (assertions
                                                   "include the push function"
                                                   (contains? env :push) => true
                                                   "include the raw sente message"
                                                   (contains? env :sente-message) => true
                                                   "include parser in the environment"
                                                   (contains? env :parser) => true
                                                   "include extra websocket items in the environment"
                                                   (contains? env :database) => true))
                                               {:result :ok})

      (ws/sente-event-handler mock-websockets (event :chsk/uidport-open :user-1))
      (ws/sente-event-handler mock-websockets (event :chsk/uidport-close :user-1))
      (ws/sente-event-handler mock-websockets (event :fulcro.client/API :user-1 [:result])))

    (assertions
      "notifies all listeners when a client connects"
      @adds => #{:user-1}
      "notifies all listeners when a client disconnects"
      @drops => #{:user-1}
      "Returns the API parser's result to the clint through sente reply"
      @reply => {:result :ok})))
