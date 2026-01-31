(ns todomvc-pure.api
  "TodoMVC API mutations for pure Fulcro.

   This namespace contains all the mutations used by the TodoMVC application.
   These work with both the pure and React-based versions of Fulcro."
  (:require
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [taoensso.timbre :as log]))

;; =============================================================================
;; State Manipulation Helpers
;; =============================================================================

(defn add-item-to-list*
  "Add an item's ident onto the end of the given list."
  [state-map list-id item-id]
  (update-in state-map [:list/id list-id :list/items] (fnil conj []) [:item/id item-id]))

(defn create-item*
  "Create a new todo item and insert it into the todo item table."
  [state-map id text]
  (assoc-in state-map [:item/id id] {:item/id id :item/label text :item/complete false}))

(defn set-item-checked*
  "Set the checked state of an item."
  [state-map id checked?]
  (assoc-in state-map [:item/id id :item/complete] checked?))

(defn clear-list-input-field*
  "Clear the main input field of the todo list."
  [state-map id]
  (assoc-in state-map [:list/id id :ui/new-item-text] ""))

(defn set-item-label*
  "Set the given item's label."
  [state-map id text]
  (assoc-in state-map [:item/id id :item/label] text))

(defn remove-from-idents
  "Given a vector of idents and an id, return a vector of idents that have none that use that ID."
  [vec-of-idents id]
  (vec (filter (fn [ident] (not= id (second ident))) vec-of-idents)))

(defn on-all-items-in-list
  "Run the xform on all of the todo items in the list with list-id."
  [state-map list-id xform & args]
  (let [item-idents (get-in state-map [:list/id list-id :list/items])]
    (reduce (fn [s idt]
              (let [id (second idt)]
                (apply xform s id args))) state-map item-idents)))

;; =============================================================================
;; Mutations
;; =============================================================================

(defmutation todo-new-item
  "Create a new todo item and add it to the list."
  [{:keys [list-id id text]}]
  (action [{:keys [state]}]
    (swap! state #(-> %
                    (create-item* id text)
                    (add-item-to-list* list-id id)
                    (clear-list-input-field* list-id))))
  (remote [_] true))

(defmutation todo-check
  "Check the given item, by id."
  [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state set-item-checked* id true))
  (remote [_] true))

(defmutation todo-uncheck
  "Uncheck the given item, by id."
  [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state set-item-checked* id false))
  (remote [_] true))

(defmutation commit-label-change
  "Commit the given text as the new label for the item with id."
  [{:keys [id text]}]
  (action [{:keys [state]}]
    (swap! state set-item-label* id text))
  (remote [_] true))

(defmutation todo-delete-item
  "Delete a todo item from the list."
  [{:keys [list-id id]}]
  (action [{:keys [state]}]
    (swap! state #(-> %
                    (update-in [:list/id list-id :list/items] remove-from-idents id)
                    (update :item/id dissoc id))))
  (remote [_] true))

(defmutation todo-check-all
  "Check all items in the list."
  [{:keys [list-id]}]
  (action [{:keys [state]}]
    (swap! state on-all-items-in-list list-id set-item-checked* true))
  (remote [_] true))

(defmutation todo-uncheck-all
  "Uncheck all items in the list."
  [{:keys [list-id]}]
  (action [{:keys [state]}]
    (swap! state on-all-items-in-list list-id set-item-checked* false))
  (remote [_] true))

(defmutation todo-clear-complete
  "Clear all completed items from the list."
  [{:keys [list-id]}]
  (action [{:keys [state]}]
    (let [is-complete? (fn [item-ident] (get-in @state (conj item-ident :item/complete)))]
      (swap! state update-in [:list/id list-id :list/items]
        (fn [todos] (vec (remove (fn [ident] (is-complete? ident)) todos))))))
  (remote [_] true))

(defmutation todo-filter
  "Change the filter on the todo list."
  [{:keys [filter list-id]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [:list/id list-id :list/filter] filter)))

(defmutation set-new-item-text
  "Update the new item text field."
  [{:keys [list-id text]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [:list/id list-id :ui/new-item-text] text)))

(defmutation start-editing
  "Start editing an item."
  [{:keys [id]}]
  (action [{:keys [state]}]
    (let [current-label (get-in @state [:item/id id :item/label])]
      (swap! state #(-> %
                      (assoc-in [:item/id id :ui/editing] true)
                      (assoc-in [:item/id id :ui/edit-text] current-label))))))

(defmutation cancel-editing
  "Cancel editing an item."
  [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state #(-> %
                    (assoc-in [:item/id id :ui/editing] false)
                    (assoc-in [:item/id id :ui/edit-text] "")))))

(defmutation update-edit-text
  "Update the edit text while editing."
  [{:keys [id text]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [:item/id id :ui/edit-text] text)))
