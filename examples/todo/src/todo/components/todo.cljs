(ns todo.components.todo
  (:require
    [quiescent-model.state :as state]
    [quiescent-model.events :as evt]
    [quiescent.core :as q :include-macros true]
    [quiescent.dom :as d]
    [todo.components.todo-item :as i]
    cljs.pprint
    )
  (:require-macros [quiescent-model.component :as c])
  )

(defn make-todolist
  ([] (make-todolist []))
  ([initial-items]
     {
      :new-item-label ""
      :filter :all
      :items (vec initial-items)
      })
  )

(defn add-item [todolist]
  (if (empty? (:new-item-label todolist))
    todolist
  (let [old-items (:items todolist)
        text (:new-item-label todolist)
        item (i/new-item text)
        new-items (cons item old-items)]
    (assoc todolist 
           :new-item-label ""
           :items new-items)
    )))

(defn check-all [todolist]
  (assoc todolist :items 
    (for [item (:items todolist)] (i/set-checked item))
    ))

(defn uncheck-all [todolist]
  (assoc todolist :items 
    (for [item (:items todolist)] (i/set-unchecked item))
    ))

(defn toggle-all [todolist]
  (if (every? i/is-checked (:items todolist))
    (uncheck-all todolist)
    (check-all todolist)))

(defn set-filter [choice todolist] (assoc todolist :filter choice))

(defn set-new-item-label [text todolist] (assoc todolist :new-item-label text))

(c/defscomponent Todo
                 "A Todo list"
                 [data context op]
                 (d/div {}
                        (cljs.pprint/pprint data)
                        (for [idx (range 0 (count (:items data)))]
                          (i/TodoItem [:items idx] context)
                          )
                   )
                 )
