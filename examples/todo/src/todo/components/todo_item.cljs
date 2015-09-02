(ns todo.components.todo-item
  (:require
    [untangled.state :as state]
    [untangled.events :as evt]
    [quiescent.core :as q :include-macros true]
    [quiescent.dom :as d]
    [todo.events :refer [enter-key? text-value]]
    [todo.components.input :refer [text-input]]
    [clojure.string :as string]
    [cljs-uuid-utils.core :as uuid]
    cljs.pprint
    )
  (:require-macros [untangled.component :as c])
  )

(defn new-item
  [text]
  {
   :id      (uuid/uuid-string (uuid/make-random-uuid)) ; make sure react can tell things apart
   :checked false
   :editing false
   :old-value ""
   :label   text
   })

(defn set-label [t item] (assoc item :label t))
(defn is-checked [item] (:checked item))
(defn set-checked [item] (assoc item :checked true))
(defn set-unchecked [item] (assoc item :checked false))
(defn toggle [item] (update item :checked not))
(defn start-editing [item] (assoc item :editing true :old-label (:label item)))
(defn commit-edit [item] (assoc item :editing false :old-label ""))
(defn cancel-edit [item] (assoc item :editing false :label (:old-label item)))

(defn item-class [item]
  (string/join " " (cond-> []
                           (:editing item) (conj "editing")
                           (:checked item) (conj "completed")
                           ))
  )

(c/defscomponent TodoItem
                 "A Todo item"
                 :keyfn :id
                 [data context]
                 (let [op (state/op-builder context)
                       delete-me #(evt/trigger context [:delete-me])] ; triggering an event that has no local state chg
                   (d/li {:className (item-class data)}
                         (d/div {:className "view"}
                                (d/input {:className "toggle" :type "checkbox" :checked (:checked data) :onChange (op toggle)})
                                (d/label {:onDoubleClick (op start-editing)} (:label data))
                                (d/button {:className "destroy" :onClick delete-me} "")
                                )
                         (text-input {:className "edit"} (:label data) (op commit-edit) (op cancel-edit) set-label op)
                         )))
