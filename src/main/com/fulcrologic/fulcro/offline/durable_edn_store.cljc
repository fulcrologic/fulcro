(ns com.fulcrologic.fulcro.offline.durable-edn-store
  "A protocol for storing/loading EDN from some kind of durable storage. This protocol is used by the offline support
   to isolate the need to persist data from the runtime environment, since Fulcro may be running in node/electron, a
   browser, or a mobile device."
  (:require
    [clojure.core.async :as async]))

(defprotocol DurableEDNStore
  (-save-edn! [this id edn]
    "Save the given `edn` using `id`. Returns a channel containing a boolean that
    indicates success.")
  (-all-ids [this] "Returns a channel that has a sequence of all of the ids available in this store.")
  (-load-all [this] "Returns a channel that contains a vector of maps `{:id id :value edn}` for everything in this store.")
  (-load-edn! [this id] "Returns a channel that contains the EDN stored under the given `id`.")
  (-delete! [this id] "Delete the given item from the store. Returns boolean to indicate success.")
  (-exists? [this id] "Returns a channel containing a boolean that indicates if the item exists in the store.")
  (-update-edn! [this id xform] "Updates the EDN stored under `id` using `(xform [edn] ...)`.
    Guaranteed not to lose the existing content on failures. Returns a channel containing a boolean that indicates success or failure."))

(defn clear-store!
  "Clear the entire content of an EDN store."
  [store]
  (async/go
    (loop [ids (async/<! (-all-ids store))]
      (when-let [id (first ids)]
        (-delete! store id)
        (recur (next ids))))))

