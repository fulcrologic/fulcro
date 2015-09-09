(ns todo.components.todo
  (:require
    [untangled.state :as state]
    [untangled.events :as evt]
    [untangled.i18n :refer-macros [tr trc trf]]
    [quiescent.core :as q :include-macros true]
    [quiescent.dom :as d]
    [todo.components.todo-item :refer [is-checked set-checked set-unchecked new-item TodoItem]]
    [todo.events :refer [enter-key? text-value]]
    [todo.components.input :refer [text-input]]
    cljs.pprint
    )
  (:require-macros [untangled.component :as c])
  )

(defn make-todolist
  ([] (make-todolist []))
  ([initial-items]
   {
    :new-item-label ""
    :filter         :all
    :items          (vec initial-items)
    })
  )

(defn cancel-add [todolist] (assoc todolist :new-item-label ""))

(defn add-item [todolist]
  (if (empty? (:new-item-label todolist))
    todolist
    (let [old-items (:items todolist)
          text (:new-item-label todolist)
          item (new-item text)
          new-items (cons item old-items)]
      (assoc todolist
        :new-item-label ""
        :items (vec new-items))
      )))

(defn has-items? [todolist] (not (empty? (:items todolist))))
(defn has-completed-items? [todolist] (some is-checked (:items todolist)))
(defn all-checked? [todolist] (every? is-checked (:items todolist)))
(defn check-all [todolist] (assoc todolist :items (vec (map set-checked (:items todolist)))))

(defn uncheck-all [todolist]
  (assoc todolist :items
                  (vec (for [item (:items todolist)] (set-unchecked item)))
                  ))

(defn toggle-all [todolist]
  (if (every? is-checked (:items todolist))
    (uncheck-all todolist)
    (check-all todolist)))

(defn set-filter [choice todolist] (assoc todolist :filter choice))

(defn set-new-item-label [text todolist] (assoc todolist :new-item-label text))

(defn filtered-items [choice items]
  (cond
    (= choice :completed) (filter is-checked items)
    (= choice :incomplete) (filter (comp not is-checked) items)
    :else items
    )
  )

(defn selected-filter-class [selected-filter todolist]
  (let [f (:filter todolist)]
    (if (= selected-filter f)
      "selected"
      ""
      )
    )
  )

(defn delete-completed-items [todolist] (assoc todolist :items (vec (filter (comp not is-checked) (:items todolist)))))
(defn delete-item [item todolist] (assoc todolist :items (vec (filter #(not= item %) (:items todolist)))))
(defn item-path [item] [:items :id (:id item)])

(c/defscomponent
  Todo
  "A Todo list"
  ; you can get an op-builder like so in the lifecycle methods:
  ;:on-mount (fn [ele-dom data context]
              ;(let [op (state/op-builder context)]
                ;(js/setInterval (op toggle-all) 2000)
                ;))
  [todo-list context]
  (let [op (state/op-builder context)
        which-filter (:filter todo-list)
        filter-all (op (partial set-filter :all))
        filter-complete (op (partial set-filter :completed))
        filter-incomplete (op (partial set-filter :incomplete))
        items (:items todo-list)
        visible-items (filtered-items which-filter items)
        incomplete-count (->> items (filter (comp not :checked)) (count))
        toggle-all-handler (op toggle-all)
        clear-completed (op delete-completed-items)
        delete-item-handler (fn [item] (op (partial delete-item item)))
        filter-ui (fn [kw activation-function label]
                    (d/li {} (d/a {:className (selected-filter-class kw todo-list) :onClick activation-function} label)))
        ]
    (d/div {}
           (d/div {:className "todoapp"}
                  (d/section {:className "todoapp"}
                             (d/header {:className "header"}
                                       (d/h1 {} "todos")
                                       (text-input {:className "new-todo" :placeholder (trf "What needs to be done?" 1 2 3)}
                                                   (:new-item-label todo-list) (op add-item) (op cancel-add) set-new-item-label op)
                                       )
                             (d/section {:className "main"}
                                        (d/input {:className "toggle-all" :type "checkbox" :checked (all-checked? todo-list) 
                                                  :onChange toggle-all-handler})
                                        (d/label {:htmlFor "toggle-all"} (tr "Mark all as complete"))
                                        (d/ul {:className "todo-list"}
                                              (map #(TodoItem (item-path %) context {:delete-me (delete-item-handler %)})
                                                   visible-items)
                                              ))
                             (if (has-items? todo-list)
                               (d/footer {:className "footer"}
                                         (d/span {:className "todo-count"} (d/strong {} incomplete-count) " items left.")
                                         (d/ul {:className "filters"}
                                               (filter-ui :all filter-all "All")
                                               (filter-ui :completed filter-complete "Completed")
                                               (filter-ui :incomplete filter-incomplete "Incomplete")
                                               )
                                         (if (has-completed-items? todo-list)
                                           (d/button {:className "clear-completed" :onClick clear-completed} "Clear completed"))
                                         ))

                             ))))
  )
