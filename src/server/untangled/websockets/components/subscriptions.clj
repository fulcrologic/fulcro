(ns untangled.websockets.components.subscriptions
  (:require [com.stuartsierra.component :as component]
            [untangled.websockets.protocols :refer [Subscribable unsubscribe]]))

(defrecord SubscriptionContainer [subscriptions]
  Subscribable
  (get-subscribers [this topic]
    (get @subscriptions topic #{}))
  (subscribe [this user topic]
    (swap! subscriptions update topic (fnil conj #{}) user))
  (unsubscribe [this user]
    (map (fn [topic]
           (unsubscribe this user topic))
      (keys @subscriptions)))
  (unsubscribe [this user topic]
    (swap! subscriptions update topic disj user))


  component/Lifecycle
  (start [component]
    (assoc component :subscriptions (atom {})))
  (stop [component]
    (dissoc component :subscriptions)))

(defn make-subscription-container []
  (component/using
    (map->SubscriptionContainer {})
    []))
