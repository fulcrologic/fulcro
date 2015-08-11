(ns todo.components.todo-item
  (:require
    [quiescent-model.state :as state]
    [quiescent-model.events :as evt]
    [quiescent.core :as q :include-macros true]
    [quiescent.dom :as d]
    [todo.events :refer [enter-key? text-value]]
    [todo.components.input :refer [text-input]]
    cljs.pprint
    )
  (:require-macros [quiescent-model.component :as c])
  )

(defn new-item
  [text]
     {
      :checked false
      :editing false
      :label text
      })

(defn set-label [t item] (assoc item :label t))
(defn is-checked [item] 
  (cljs.pprint/pprint item)
  (:checked item))
(defn set-checked [item] (assoc item :checked true))
(defn set-unchecked [item] (assoc item :checked false))
(defn toggle [item] (update item :checked not))
(defn start-editing [item] (assoc item :editing true))
(defn commit-edit [item] (assoc item :editing false))

(defn input-value-handler [evt] )

(c/defscomponent TodoItem
                 "A Todo item"
                 :key-fn :label
                 [data context op]
                 (let [toggle-item (op toggle)
                       edit-text (op start-editing)
                       finish-edit (op commit-edit)
                       ]
                   (d/div { :className (if (is-checked data) "done" "") }
                    (d/input {:type "checkbox" :checked (:checked data) :onChange toggle-item})
                    (if (:editing data)
                      (text-input (:label data) finish-edit set-label op)
                      (d/span { :onDoubleClick edit-text } (:label data))
                      )
                    )))
