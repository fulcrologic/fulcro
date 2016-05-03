(ns untangled.websockets.protocols)

(defprotocol ChannelHandler
  (req->authenticated? [this req client-tab-uuid]
    "Returns a bool describing whether or not authentication succeeded.")
  (client-dropped [this client-tab-uuid]
    "Handles dropping a client connection."))

(defprotocol Subscribable
  (subscribe [this user topic]
    "Subscribe a user to a topic")
  (unsubscribe [this user] [this user topic]
    "Unsubscribe a user from all topics or a topic")
  (get-subscribers [this topic]
    "Get the subscribers to a topic."))
