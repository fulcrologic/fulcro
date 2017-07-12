(ns fulcro.websockets.protocols)

(defprotocol WSListener
  (client-added [this ws-net cid]
    "Listener for dealing with client added events.")
  (client-dropped [this ws-net cid]
    "listener for dealing with client dropped events."))

(defprotocol WSNet
  (add-listener [this ^WSListener listener]
    "Add a `WSListener` listener")
  (remove-listener [this ^WSListener listener]
    "Remove a `WSListener` listener")
  (push [this cid verb edn] "Push from server"))
