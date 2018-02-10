(ns solutions.putting-together
  (:require [fulcro.easy-server :as easy]
            [fulcro.client.primitives :as prim]
            [fulcro.logging :as log]
            [fulcro.server :refer [defmutation defquery-root defquery-entity]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SOLUTIONS ARE BELOW.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(comment
  ; SETTING UP:
  (def system (atom nil))

  (defn make-server []
    (fulcro.easy-server/make-fulcro-server :config-path "config/exercise.edn"))

  ; A query to test the server
  (defquery-root :something
    (value [env params] 66))

  ; EXERCISE 3: Support Mutations on Server

  ;; Database format is just a map of todo items by ID. Sorting them by ID maintains order
  (def items (atom {}))
  (def next-id (atom 0))
  (defn get-next-id [] (swap! next-id inc))

  (defmutation todo/add-item [{:keys [id label]}]
    (action [env]
      (let [new-id (get-next-id)
            item   {:item/id new-id :item/label label :item/done false}]
        (swap! items assoc new-id item)
        (log/info "Added item: " item)
        {::prim/tempids {id new-id}})))

  (defmutation todo/toggle-done [{:keys [id done?]}]
    (action [e]
      ; extra credit: client will send what it toggled to
      (log/info "toggled item: " id " to " done?)
      (swap! items assoc-in [id :item/done] done?)))

  (defmutation todo/delete-item [{:keys [id]}]
    (action [e]
      (swap! items dissoc id)))

  ;; SOLUTION TO EX4: Read the items from the server on startup
  (defquery-root :ex4/all-items
    (value [env p] (into [] (sort-by :item/id (vals @items))))))
