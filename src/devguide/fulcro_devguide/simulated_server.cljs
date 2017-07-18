(ns fulcro-devguide.simulated-server
  (:require
    [om.next :as om]
    [fulcro.client.network :as fcn]))

(defrecord MockNetwork [server]
  fcn/FulcroNetwork
  (send [this edn ok err]
    (let [resp (server {} edn)]
      (js/setTimeout #(ok resp) 700)))
  (start [this app] this))

(defn make-mock-network [state read+mutate]
  (->MockNetwork
    (let [parser (om/parser read+mutate)]
      (fn [env tx] (parser (assoc env :state state) tx)))))
