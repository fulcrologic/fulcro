(ns com.fulcrologic.fulcro.offline.tempid-strategy
  "Temporary ID strategy. Used by offline support to enable disconnected applications to define how Fulcro tempids
   behave when disconnected. The support allows a client decide to implement ID support in whatever way
   makes sense to the feature set of the application in offline modes.

   * Negotiate a pre-allocated pool of IDs with the server when online.
   * Use client-generated UUIDs
   * Use the UUIDs within the tempids as the real IDs on the server
   * etc.

  This ns also includes two pre-written strategies:

  TempIDisRealIDStrategy:: This strategy immediately rewrites the tempids in app state to the UUID *within* the tempid. This allows
  the client to assign the real ID of the entity immediately, and the server must then either honor that assignment or remap it
  on success. The server will receive the tempid. This is a very easy way to ensure idempotent save as well, since the
  server can easily detect when that an incoming tempid has already been saved under that UUID in the database.

  NOOPStrategy:: Does nothing. The IDs remain temporary in app state and in the txn sent to the server. Rewrites happen
  on success."
  (:require
    [clojure.walk :as walk]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid #?@(:cljs [:refer [TempId]])])
  #?(:clj
     (:import
       [com.fulcrologic.fulcro.algorithms.tempid TempId])))

(defprotocol TempidStrategy
  (-rewrite-txn [this txn resolved-tempids]
    "This operation is called in order to rewrite the outgoing transaction that will eventually be seen by the server.
     It could simply return `txn`, it could rewrite the tempids in the `txn`, or it could add something to tell the
     server about how it resolved the tempids on the client by augmenting the parameters of the mutations in the transaction.

     It will be given the *original* `txn`, the `resolved-tempids` (see `resolve-tempids`), and must return a
     `txn`.

     NOTE: This function does not have to remap the tempids in `txn`. For example, say you are using the uuids within
     Fulcro tempids as the real IDs. The server can be written to do that, so there would be no need to do any
     transformation of the `txn` on the client.")
  (-resolve-tempids [this txn]
    "This function will be given the user-submitted `txn`. It should return a map for any tempid remappings that
     are desired immediately, where the keys of the returned map are tempids that appear in the the `txn`,
     and whose values are the remapping of those values. If this function returns nothing, then the tempids
     will remain in app state until the real server remapping is done."))

;; A strategy that assume the UUIDs within the Fulcro tempid will be used as the real ID on the server.
(deftype TempIDisRealIDStrategy []
  TempidStrategy
  (-rewrite-txn [_ txn _] txn)
  (-resolve-tempids [_ txn]
    (let [ids (atom #{})
          _   (walk/prewalk (fn [x]
                              (when (tempid/tempid? x)
                                (swap! ids conj x))
                              x) txn)
          ids @ids]
      (zipmap ids (map (fn [^TempId t] (.-id t)) ids)))))

;; A strategy that leaves the tempids in app state until the mutation succeeds. NOTE: Form state will not consider a
;; form "clean" until the tempids are rewritten, so using this strategy will require you to augment form-state algorithms
;; if you use them.
(deftype NOOPStrategy []
  TempidStrategy
  (-rewrite-txn [_ txn _] txn)
  (-resolve-tempids [_ txn] {}))
