(ns untangled.websockets.protocols)

(defprotocol ChannelHandler
  (req->authenticated? [this req client-tab-uuid]
    "Returns a bool describing whether or not authentication succeeded.")
  (client-dropped [this client-tab-uuid]
    "Handles dropping a client connection."))
