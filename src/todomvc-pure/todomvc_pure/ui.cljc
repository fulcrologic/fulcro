(ns todomvc-pure.ui
  "TodoMVC UI components using pure Fulcro (no React/NPM).

   This namespace demonstrates how to build a full TodoMVC application
   using pure Fulcro components that render to Element records instead
   of React components.

   The components can be rendered to hiccup and then to the DOM via
   Replicant or similar libraries.

   NOTE: The component code is nearly identical to the React version.
   The key differences are:
   1. Requires use pure/ namespaces instead of React-based ones
   2. No lifecycle methods (initLocalState, componentDidUpdate, etc.)
   3. No refs (React-specific)
   4. Uses explicit mutations instead of mut/set-string! helpers"
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.tempid :as tmp]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.pure.dom :as dom]
    [com.fulcrologic.fulcro.pure.replicant :refer [defsc factory]]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [todomvc-pure.api :as api]))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn trim-text
  "Returns text without surrounding whitespace if not empty, otherwise nil."
  [text]
  (let [trimmed-text (str/trim (or text ""))]
    (when-not (empty? trimmed-text)
      trimmed-text)))

(defn css-safe-id
  "Converts an id value to a CSS-safe string suitable for use in HTML id attributes.
   Handles tempids by extracting just their UUID portion.
   Regular values are converted to strings."
  [id]
  (if (tmp/tempid? id)
    (str "temp-" #?(:clj (:id id) :cljs (.-id id)))
    (str id)))

;; =============================================================================
;; TodoItem Component
;; =============================================================================

(defsc TodoItem [this {:item/keys [id label complete] :ui/keys [editing edit-text]
                       :or        {complete false editing false}
                       :as        props}]
  {:query (fn [] [:item/id :item/label :item/complete :ui/editing :ui/edit-text])
   :ident (fn [_ props] [:item/id (:item/id props)])}
  (let [{:keys [delete-item]} (rc/get-computed props)
        submit-edit (fn []
                      (if-let [trimmed-text (trim-text edit-text)]
                        (do
                          (rc/transact! this [(api/commit-label-change {:id id :text trimmed-text})])
                          (rc/transact! this [(api/cancel-editing {:id id})]))
                        (delete-item id)))]
    (dom/li {:className (str (when complete "completed") (when editing " editing"))}
      (dom/div {:className "view"}
        (dom/input {:type      "checkbox"
                    :className "toggle"
                    :id        (str "toggle-" (css-safe-id id))
                    :checked   (boolean complete)
                    :onChange  (fn [_]
                                 (if complete
                                   (rc/transact! this [(api/todo-uncheck {:id id})])
                                   (rc/transact! this [(api/todo-check {:id id})])))})
        (dom/label {:onDoubleClick (fn [_]
                                     (rc/transact! this [(api/start-editing {:id id})]))}
          label)
        (dom/button {:className "destroy"
                     :id        (str "delete-" (css-safe-id id))
                     :onClick   (fn [_] (delete-item id))}))
      (when editing
        (dom/input {:className "edit"
                    :id        (str "edit-" (css-safe-id id))
                    :value     (or edit-text "")
                    :onChange  (fn [e]
                                 (let [value (evt/target-value e)]
                                   (rc/transact!! this [(api/update-edit-text {:id id :text value})])))
                    :onKeyDown (fn [e]
                                 (cond
                                   (evt/enter-key? e) (submit-edit)
                                   (evt/escape-key? e) (rc/transact! this [(api/cancel-editing {:id id})])))
                    :onBlur    (fn [_] (when editing (submit-edit)))})))))

(def ui-todo-item (factory TodoItem {:keyfn :item/id}))

;; =============================================================================
;; Header Component
;; =============================================================================

(defn header
  "Render the header with the input for new todos."
  [component title]
  (let [{:list/keys [id] :ui/keys [new-item-text]} (rc/props component)]
    (dom/header {:className "header"}
      (dom/h1 {} title)
      (dom/input {:className   "new-todo"
                  :id          "new-todo-input"
                  :value       (or new-item-text "")
                  :placeholder "What needs to be done?"
                  :autoFocus   true
                  :onKeyUp   (fn [e]
                                 (if (evt/enter-key? e)
                                   (when-let [trimmed-text (trim-text (evt/target-value e))]
                                     (rc/transact! component [(api/todo-new-item {:list-id id
                                                                                  :id      (tmp/tempid)
                                                                                  :text    trimmed-text})]))
                                   (rc/transact!! component [(api/set-new-item-text {:list-id id :text (evt/target-value e)})])))}))))

;; =============================================================================
;; Footer Component
;; =============================================================================

(defn filter-footer
  "Render the footer with filter controls."
  [component num-todos num-completed]
  (let [{:list/keys [id filter]} (rc/props component)
        num-remaining (- num-todos num-completed)]
    (dom/footer {:className "footer"}
      (dom/span {:className "todo-count"}
        (dom/strong {} (str num-remaining))
        (str (if (= 1 num-remaining) " item" " items") " left"))
      (dom/ul {:className "filters"}
        (dom/li {}
          (dom/a {:className (when (or (nil? filter) (= :list.filter/none filter)) "selected")
                  :id        "filter-all"
                  :href      "#"
                  :onClick   (fn [_] (rc/transact! component [(api/todo-filter {:list-id id :filter :list.filter/none})]))}
            "All"))
        (dom/li {}
          (dom/a {:className (when (= :list.filter/active filter) "selected")
                  :id        "filter-active"
                  :href      "#/active"
                  :onClick   (fn [_] (rc/transact! component [(api/todo-filter {:list-id id :filter :list.filter/active})]))}
            "Active"))
        (dom/li {}
          (dom/a {:className (when (= :list.filter/completed filter) "selected")
                  :id        "filter-completed"
                  :href      "#/completed"
                  :onClick   (fn [_] (rc/transact! component [(api/todo-filter {:list-id id :filter :list.filter/completed})]))}
            "Completed")))
      (when (pos? num-completed)
        (dom/button {:className "clear-completed"
                     :id        "clear-completed"
                     :onClick   (fn [_] (rc/transact! component [(api/todo-clear-complete {:list-id id})]))}
          "Clear completed")))))

(defn footer-info
  "Render the info footer."
  []
  (dom/footer {:className "info"}
    (dom/p {} "Double-click to edit a todo")
    (dom/p {}
      "Created by "
      (dom/a {:href   "http://www.fulcrologic.com"
              :target "_blank"}
        "Fulcrologic, LLC"))
    (dom/p {}
      "Part of "
      (dom/a {:href   "http://todomvc.com"
              :target "_blank"}
        "TodoMVC"))))

;; =============================================================================
;; TodoList Component
;; =============================================================================

(defsc TodoList [this {:list/keys [id items filter title] :as props}]
  {:initial-state (fn [_ params] {:list/id          1
                                  :ui/new-item-text ""
                                  :list/items       []
                                  :list/title       "todos"
                                  :list/filter      :list.filter/none})
   :ident         (fn [this props] [:list/id (:list/id props)])
   :query         (fn [] [:list/id :ui/new-item-text {:list/items (rc/get-query TodoItem)} :list/title :list/filter])}
  (let [num-todos       (count items)
        completed-todos (filterv :item/complete items)
        num-completed   (count completed-todos)
        all-completed?  (and (pos? num-todos) (every? :item/complete items))
        filtered-todos  (case filter
                          :list.filter/active (filterv (comp not :item/complete) items)
                          :list.filter/completed completed-todos
                          items)
        delete-item     (fn [item-id]
                          (rc/transact! this [(api/todo-delete-item {:list-id id :id item-id})]))]
    (dom/div {}
      (dom/section {:className "todoapp"}
        (header this title)
        (when (pos? num-todos)
          (dom/div {}
            (dom/section {:className "main"}
              (dom/input {:type      "checkbox"
                          :className "toggle-all"
                          :id        "toggle-all"
                          :checked   all-completed?
                          :onChange  (fn [_]
                                       (if all-completed?
                                         (rc/transact! this [(api/todo-uncheck-all {:list-id id})])
                                         (rc/transact! this [(api/todo-check-all {:list-id id})])))})
              (dom/label {:htmlFor "toggle-all"} "Mark all as complete")
              (dom/ul {:className "todo-list"}
                (mapv #(ui-todo-item (rc/computed % {:delete-item delete-item})) filtered-todos)))
            (filter-footer this num-todos num-completed))))
      (footer-info))))

(def ui-todo-list (factory TodoList))

;; =============================================================================
;; Root Component
;; =============================================================================

(defsc Root [this {:root/keys [todo]}]
  {:initial-state (fn [] {:root/todo (rc/get-initial-state TodoList {})})
   :query         (fn [] [{:root/todo (rc/get-query TodoList)}])}
  (dom/div {:id "app-root"}
    (ui-todo-list todo)))
