(ns untangled.websockets.protocols)

(defprotocol WSNet
  (add-listener [this ^WSListener listener]
    "Add a `WSListen` listener")
  (remove-listener [this ^WSListener listener]
    "Remove a `WSListen` listener")
  (push [this cid verb edn] "Push from server"))

(defprotocol WSListener
  (client-added [this ws-net cid]
    "Listener for dealing with client added events.")
  (client-dropped [this ws-net cid]
    "listener for dealing with client dropped events."))
