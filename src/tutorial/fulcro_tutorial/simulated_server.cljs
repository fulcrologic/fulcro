(ns fulcro-tutorial.simulated-server
  (:require
    [fulcro.client.primitives :as prim]
    [fulcro.client.network :as fcn]))

(defrecord MockNetwork [server]
  fcn/FulcroNetwork
  (send [this edn ok err]
    (let [resp (server {} edn)]
      (js/setTimeout #(ok resp) 700)))
  (start [this] this))

(defn make-mock-network [state read+mutate]
  (->MockNetwork
    (let [parser (prim/parser read+mutate)]
      (fn [env tx] (parser (assoc env :state state) tx)))))
