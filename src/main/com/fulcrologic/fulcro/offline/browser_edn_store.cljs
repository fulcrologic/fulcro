(ns com.fulcrologic.fulcro.offline.browser-edn-store
  "An implementation of a DurableEDNStore that uses Browser local storage to save/load the EDN."
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.fulcro.offline.durable-edn-store :as des]
    [com.fulcrologic.fulcro.algorithms.transit :as transit]
    [taoensso.timbre :as log]
    [clojure.string :as str]))

(declare id-of key-of)

(deftype BrowserEDNStore [prefix]
  des/DurableEDNStore
  (-save-edn! [this id edn]
    (async/go
      (try
        (let [store js/localStorage
              k     (key-of this id)
              value (transit/transit-clj->str edn {:metadata? false})]
          (.setItem store k value)
          true)
        (catch :default e
          (log/error e "Local storage denied." edn "See https://book.fulcrologic.com/#err-edn-store-denied")
          false))))
  (-load-all [this]
    (async/go
      (try
        (mapv
          (fn [id] {:id id :value (some-> (.getItem js/localStorage (key-of this id)) (transit/transit-str->clj))})
          (async/<! (des/-all-ids this)))
        (catch :default e
          (log/error e "Cannot list items in storage. See https://book.fulcrologic.com/#err-edn-store-list-failed")
          []))))
  (-all-ids [this]
    (async/go
      (try
        (let [nitems (.-length js/localStorage)]
          (vec
            (keep
              (fn [k] (when (str/starts-with? k prefix)
                        (id-of this k)))
              (for [idx (range nitems)] (.key js/localStorage idx)))))
        (catch :default e
          (log/error e "Cannot list items in storage. See https://book.fulcrologic.com/#err-edn-store-list-failed")
          []))))
  (-load-edn! [this id]
    (async/go
      (try
        (let [k (key-of this id)]
          (some-> (.getItem js/localStorage k) (transit/transit-str->clj)))
        (catch :default e
          (log/error e "Load failed. See https://book.fulcrologic.com/#err-edn-store-load-failed")
          nil))))
  (-exists? [this id]
    (async/go
      (let [k (key-of this id)
            v (.getItem js/localStorage k)]
        (not (or (undefined? v) (nil? v))))))
  (-delete! [this id]
    (async/go
      (try
        (let [k (key-of this id)]
          (.removeItem js/localStorage k)
          true)
        (catch :default e
          (log/error e "Delete failed. See https://book.fulcrologic.com/#err-edn-store-delete-failed")
          false))))
  (-update-edn! [this id xform]
    (async/go
      (try
        (let [old (async/<! (des/-load-edn! this id))
              new (xform old)]
          (when-not (nil? new)
            (des/-save-edn! this id new)))
        (catch :default e
          (log/error e "Cannot update edn. See https://book.fulcrologic.com/#err-edn-store-update-failed"))))))

(defn- id-of [^BrowserEDNStore store store-key]
  (let [prefix (.-prefix store)
        n      (inc (count prefix))]
    (subs store-key n)))

(defn- key-of [^BrowserEDNStore store nm]
  (str (.-prefix store) "-" nm))

(defn browser-edn-store
  "Stores EDN as transit-encoded strings in browser-local storage. `prefix` is a string that will be used to make sure
   the names used in browser local storage are not confused with other things that might be in there."
  [prefix]
  (->BrowserEDNStore prefix))

(comment
  (def store (browser-edn-store "test-store"))
  (defonce k (random-uuid))
  (async/go
    (js/console.log (async/<! (des/-save-edn! store "tony" {:x 22}))))
  (async/go
    (js/console.log (async/<! (des/-exists? store "tony"))))
  (async/go
    (js/console.log (async/<! (des/-all-ids store))))
  (async/go
    (js/console.log (async/<! (des/-load-edn! store "tony"))))
  (async/go
    (js/console.log (async/<! (des/-delete! store "tony"))))
  (async/go
    (js/console.log (async/<! (des/-load-all store)))))
