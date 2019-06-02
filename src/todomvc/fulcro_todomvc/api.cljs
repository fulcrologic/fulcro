(ns fulcro-todomvc.api
  (:require
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.algorithms.data-targeting :as dt]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [clojure.string :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Client-side API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-item-to-list*
  "Add an item's ident onto the end of the given list."
  [state-map list-id item-id]
  (update-in state-map [:list/id list-id :list/items] (fnil conj []) [:item/id item-id]))

(defn create-item*
  "Create a new todo item and insert it into the todo item table."
  [state-map id text]
  (assoc-in state-map [:item/id id] {:item/id id :item/label text}))

(defn set-item-checked*
  [state-map id checked?]
  (assoc-in state-map [:item/id id :item/complete] checked?))

(defn clear-list-input-field*
  "Clear the main input field of the todo list"
  [state-map id]
  (assoc-in state-map [:list/id id :ui/new-item-text] ""))

(defmutation todo-new-item [{:keys [list-id id text]}]
  (action [{:keys [state ast]}]
    (swap! state #(-> %
                    (create-item* id text)
                    (add-item-to-list* list-id id)
                    (clear-list-input-field* list-id))))
  (remote [_] true))

(defmutation todo-check [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state set-item-checked* id true))
  (remote [_] true))

(defmutation todo-uncheck [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state set-item-checked* id false))
  (remote [_] true))

(defn set-item-label*
  "Set the given item's label"
  [state-map id text]
  (assoc-in state-map [:item/id id :item/label] text))

(defmutation commit-label-change
  "Mutation: Commit the given text as the new label for the item with id."
  [{:keys [id text]}]
  (action [{:keys [state]}]
    (swap! state set-item-label* id text))
  (remote [_] true))

(defn remove-from-idents
  "Given a vector of idents and an id, return a vector of idents that have none that use that ID for their second (id) element."
  [vec-of-idents id]
  (vec (filter (fn [ident] (not= id (second ident))) vec-of-idents)))

(defmutation todo-delete-item [{:keys [list-id id]}]
  (action [{:keys [state]}]
    (swap! state #(-> %
                    (update-in [:list/id list-id :list/items] remove-from-idents id)
                    (update :item/id dissoc id))))
  (remote [_] true))

(defn on-all-items-in-list
  "Run the xform on all of the todo items in the list with list-id. The xform will be called with the state map and the
  todo's id and must return a new state map with that todo updated. The args will be applied to the xform as additional
  arguments"
  [state-map list-id xform & args]
  (let [item-idents (get-in state-map [:list/id list-id :list/items])]
    (reduce (fn [s idt]
              (let [id (second idt)]
                (apply xform s id args))) state-map item-idents)))

(defmutation todo-check-all [{:keys [list-id]}]
  (action [{:keys [state]}]
    (swap! state on-all-items-in-list list-id set-item-checked* true))
  (remote [_] true))

(defmutation todo-uncheck-all [{:keys [list-id]}]
  (action [{:keys [state]}]
    (swap! state on-all-items-in-list list-id set-item-checked* false))
  (remote [_] true))

(defmutation todo-clear-complete [{:keys [list-id]}]
  (action [{:keys [state]}]
    (let [is-complete? (fn [item-ident] (get-in @state (conj item-ident :item/complete)))]
      (swap! state update-in [:list/id list-id :list/items]
             (fn [todos] (vec (remove (fn [ident] (is-complete? ident)) todos))))))
  (remote [_] true))

(defn current-list-id [state] (get-in state [:application :root :todos 1]))

(defmutation set-desired-filter
  "Check to see if there was a desired filter. If so, put it on the now-active list and remove the desire. This is
  necessary because the HTML5 routing event comes to us on app load before we can load the list."
  [ignored]
  (action [{:keys [state]}]
    (let [list-id        (current-list-id @state)
          desired-filter (get @state :root/desired-filter)]
      (when (and list-id desired-filter)
        (swap! state assoc-in [:list/id list-id :list/filter] desired-filter)
        (swap! state dissoc :root/desired-filter)))))

(defmutation todo-filter
  "Change the filter on the active list (the one pointed to by top-level :todos). If there isn't one, stash
  it in :root/desired-filter."
  [{:keys [filter]}]
  (action [{:keys [state]}]
    (let [list-id (current-list-id @state)]
      (if list-id
        (swap! state assoc-in [:list/id list-id :list/filter] filter)
        (swap! state assoc :root/desired-filter filter)))))

