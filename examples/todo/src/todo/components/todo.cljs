(ns todo.components.todo
  (:require
    [quiescent-model.state :as state]
    [quiescent-model.events :as evt]
    [quiescent.core :as q :include-macros true]
    [quiescent.dom :as d]
    [todo.components.todo-item :refer [is-checked set-checked set-unchecked new-item TodoItem]]
    [todo.events :refer [enter-key? text-value]]
    [todo.components.input :refer [text-input]]
    cljs.pprint
    )
  (:require-macros [quiescent-model.component :as c])
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

(defn check-all [todolist]
  (assoc todolist :items
                  (for [item (:items todolist)] (set-checked item))
                  ))

(defn uncheck-all [todolist]
  (assoc todolist :items
                  (for [item (:items todolist)] (set-unchecked item))
                  ))

(defn toggle-all [todolist]
  (if (every? is-checked (:items todolist))
    (uncheck-all todolist)
    (check-all todolist)))

(defn set-filter [choice todolist] (assoc todolist :filter choice))

(defn set-new-item-label [text todolist] (assoc todolist :new-item-label text))

(defn filtered-items [choice indexed-items]
  (cond
    (= choice :completed) (filter #(is-checked (second %)) indexed-items)
    (= choice :incomplete) (filter #((comp not is-checked) (second %)) indexed-items)
    :else indexed-items
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
(c/defscomponent Todo
                 "A Todo list"
                 [data context op]
                 (let [add-the-item (op add-item)
                       filter (:filter data)
                       filter-all (op (partial set-filter :all))
                       filter-complete (op (partial set-filter :completed))
                       filter-incomplete (op (partial set-filter :incomplete))
                       indexed-items (map-indexed vector (:items data))
                       indexed-visible-items (filtered-items filter indexed-items)
                       ]
                   (d/div {:className "todo"}
                          (d/div {:className "new-item-input"}
                                 (text-input (:new-item-label data) add-the-item set-new-item-label op)
                                 )
                          (d/div {:className "items"}
                                 (map #(TodoItem [:items (first %1)] context) indexed-visible-items))
                          (d/div {:className "filter"}
                                 (d/ul {:className "filters"}
                                       (d/li {:className (selected-filter-class :all data)} 
                                             (d/a {:onClick filter-all} "All"))
                                       (d/li {:className (selected-filter-class :completed data)} 
                                             (d/a {:onClick filter-complete} "Completed"))
                                       (d/li {:className (selected-filter-class :incomplete data)} 
                                             (d/a {:onClick filter-incomplete} "Incomplete"))
                                       )
                                 )

                          ))
                 )
