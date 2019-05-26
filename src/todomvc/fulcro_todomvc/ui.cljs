(ns fulcro-todomvc.ui
  (:require
    [com.fulcrologic.fulcro.algorithms.tempid :as tmp]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :as mut :refer [defmutation]]
    [com.fulcrologic.fulcro.routing.union-router :as fr]
    [fulcro-todomvc.api :as api]
    [goog.object :as gobj]
    [taoensso.timbre :as log]))

(defn is-enter? [evt] (= 13 (.-keyCode evt)))
(defn is-escape? [evt] (= 27 (.-keyCode evt)))

(defn trim-text [text]
  "Returns text without surrounding whitespace if not empty, otherwise nil"
  (let [trimmed-text (clojure.string/trim text)]
    (when-not (empty? trimmed-text)
      trimmed-text)))

(defsc TodoItem [this
                 {:ui/keys   [ui/editing ui/edit-text]
                  :item/keys [id label complete] :or {complete false} :as props}
                 {:keys [delete-item check uncheck] :as computed}]
  {:query              (fn [this] [:item/id :item/label :item/complete :ui/editing :ui/edit-text])
   :ident              :item/id
   :initLocalState     (fn [this] {:save-ref (fn [r] (gobj/set this "input-ref" r))})
   :componentDidUpdate (fn [this prev-props _]
                         ;; Code adapted from React TodoMVC implementation
                         (when (and (not (:editing prev-props)) (:editing (comp/props this)))
                           (let [input-field        (gobj/get this "input-ref")
                                 input-field-length (when input-field (.. input-field -value -length))]
                             (when input-field
                               (.focus input-field)
                               (.setSelectionRange input-field input-field-length input-field-length)))))}
  (let [submit-edit (fn [evt]
                      (if-let [trimmed-text (trim-text (.. evt -target -value))]
                        (do
                          (comp/transact! this [(api/commit-label-change {:id id :text trimmed-text})])
                          (mut/set-string! this :ui/edit-text :value trimmed-text)
                          (mut/toggle! this :ui/editing))
                        (delete-item id)))]

    (dom/li {:classes [(when complete (str "completed")) (when editing (str " editing"))]}
      (dom/div :.view {}
        (dom/input {:type      "checkbox"
                    :ref       (comp/get-state this :save-ref)
                    :className "toggle"
                    :checked   (boolean complete)
                    :onChange  #(if complete (uncheck id) (check id))})
        (dom/label {:onDoubleClick (fn []
                                     (mut/toggle! this :ui/editing)
                                     (mut/set-string! this :ui/edit-text :value label))} label)
        (dom/button :.destroy {:onClick #(delete-item id)}))
      (dom/input {:ref       "edit_field"
                  :className "edit"
                  :value     (or edit-text "")
                  :onChange  #(mut/set-string! this :ui/edit-text :event %)
                  :onKeyDown #(cond
                                (is-enter? %) (submit-edit %)
                                (is-escape? %) (do (mut/set-string! this :ui/edit-text :value label)
                                                   (mut/toggle! this :ui/editing)))
                  :onBlur    #(when editing (submit-edit %))}))))

(def ui-todo-item (comp/factory TodoItem {:keyfn :item/id}))

(defn header [component title]
  (let [{:keys [list/id ui/new-item-text]} (comp/props component)]
    (dom/header :.header {}
      (dom/h1 {} title)
      (dom/input {:value       (or new-item-text "")
                  :className   "new-todo"
                  :onKeyDown   (fn [evt]
                                 (when (is-enter? evt)
                                   (when-let [trimmed-text (trim-text (.. evt -target -value))]
                                     (comp/transact! component `[(api/todo-new-item ~{:list-id id
                                                                                      :id      (tmp/tempid)
                                                                                      :text    trimmed-text})]))))
                  :onChange    (fn [evt] (mut/set-string! component :ui/new-item-text :event evt))
                  :placeholder "What needs to be done?"
                  :autoFocus   true}))))

(defn filter-footer [component num-todos num-completed]
  (let [{:keys [list/id list/filter]} (comp/props component)
        num-remaining (- num-todos num-completed)]

    (dom/footer :.footer {}
      (dom/span :.todo-count {}
        (dom/strong (str num-remaining " left")))
      (dom/ul :.filters {}
        (dom/li {}
          (dom/a {:className (when (or (nil? filter) (= :list.filter/none filter)) "selected")
                  :href      "#"} "All"))
        (dom/li {}
          (dom/a {:className (when (= :list.filter/active filter) "selected")
                  :href      "#/active"} "Active"))
        (dom/li {}
          (dom/a {:className (when (= :list.filter/completed filter) "selected")
                  :href      "#/completed"} "Completed")))
      (when (pos? num-completed)
        (dom/button {:className "clear-completed"
                     :onClick   #(comp/transact! component `[(api/todo-clear-complete {:list-id ~id})])} "Clear Completed")))))

(defn footer-info []
  (dom/footer :.info {}
    (dom/p {} "Double-click to edit a todo")
    (dom/p {} "Created by "
      (dom/a {:href   "http://www.fulcrologic.com"
              :target "_blank"} "Fulcrologic, LLC"))
    (dom/p {} "Part of "
      (dom/a {:href   "http://todomvc.com"
              :target "_blank"} "TodoMVC"))))

(defsc TodoList [this {:list/keys [id items filter title] :as props}]
  {:initial-state {:list/id 1 :ui/new-item-text "" :list/items [] :list/title "main" :list/filter :list.filter/none}
   :ident         :list/id
   :query         [:list/id :ui/new-item-text {:list/items (comp/get-query TodoItem)} :list/title :list/filter]}
  (let [num-todos       (count items)
        completed-todos (filterv :item/complete items)
        num-completed   (count completed-todos)
        all-completed?  (= num-completed num-todos)
        filtered-todos  (case filter
                          :list.filter/active (filterv (comp not :item/complete) items)
                          :list.filter/completed completed-todos
                          items)
        delete-item     (fn [item-id] (comp/transact! this `[(api/todo-delete-item ~{:list-id id :id item-id})]))
        check           (fn [item-id] (comp/transact! this `[(api/todo-check ~{:id item-id})]))
        uncheck         (fn [item-id] (comp/transact! this `[(api/todo-uncheck ~{:id item-id})]))]
    (log/info "Shared" (comp/shared this))
    (dom/div {}
      (dom/section :.todoapp {}
        (header this title)
        (when (pos? num-todos)
          (dom/div {}
            (dom/section :.main {}
              (dom/input {:type      "checkbox"
                          :className "toggle-all"
                          :checked   all-completed?
                          :onClick   (fn [] (if all-completed?
                                              (comp/transact! this `[(api/todo-uncheck-all {:list-id ~id})])
                                              (comp/transact! this `[(api/todo-check-all {:list-id ~id})])))})
              (dom/label {:htmlFor "toggle-all"} "Mark all as complete")
              (dom/ul :.todo-list {}
                (map #(ui-todo-item (comp/computed %
                                      {:delete-item delete-item
                                       :check       check
                                       :uncheck     uncheck})) filtered-todos)))
            (filter-footer this num-todos num-completed))))
      (footer-info))))

(def ui-todo-list (comp/factory TodoList))

(defsc Application [this {:keys [todos] :as props}]
  {:initial-state (fn [p] {:route :application
                           :todos (comp/get-initial-state TodoList {})})
   :ident         (fn [] [:application :root])
   :query         [:route {:todos (comp/get-query TodoList)}]}
  (dom/div {}
    (ui-todo-list todos)))

(def ui-application (comp/factory Application))

(defsc Other [this props]
  {:query         [:route]
   :ident         (fn [this props] [:other :root])
   :initial-state {:route :other}}
  (dom/div "OTHER ROUTE"))

(fr/defsc-router TopRouter [this props]
  {:router-id      ::top-router
   :ident          (fn [this props]
                     (log/spy :info this)
                     [(:route props) :root])
   :default-route  Application
   :router-targets {:application Application
                    :other       Other}})

(def ui-router (comp/factory TopRouter))

(defsc Root [this {:root/keys [router] :as props}]
  {:initial-state (fn [p] {:root/router (comp/get-initial-state TopRouter {})})
   :query         [{:root/router (comp/get-query TopRouter)}]}
  (log/info "root props" props)
  (dom/div {}
    (dom/button {:onClick (fn [] (comp/transact! this `[(fr/set-route {:router ::top-router :target [:application :root]})]))} "App")
    (dom/button {:onClick (fn [] (comp/transact! this `[(fr/set-route {:router ::top-router :target [:other :root]})]))} "Other")
    (ui-router router)))
