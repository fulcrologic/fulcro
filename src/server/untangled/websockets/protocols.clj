(ns untangled.websockets.protocols)

(defprotocol WSEvents
  (add-closed-listener [this listener]
    "Add a listener to run when when a connection is closed.")
  (remove-closed-listener [this listener]
    "Remove a listener to run when when a connection is closed.")
  (add-opened-listener [this listener]
    "Add a listener to run when when a connection is opened.")
  (remove-opened-listener [this listener]
    "Remove a listener to run when when a connection is opened."))

(defprotocol WSPush
  (push [this cid verb edn] "Push from server"))
