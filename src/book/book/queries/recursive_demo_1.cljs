(ns book.queries.recursive-demo-1
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]))

(defn make-person
  "Make a person data map with optional children."
  [id name children]
  (cond-> {:db/id id :person/name name}
    children (assoc :person/children children)))

(declare ui-person)

; The ... in the query means there will be children of the same type, of arbitrary depth
; it is equivalent to (prim/get-query Person), but calling get query on yourself would
; lead to infinite compiler recursion.
(defsc Person [this {:keys [:person/name :person/children]}]
  {:query         (fn [] [:db/id :person/name {:person/children '...}])
   :initial-state (fn [p]
                    (make-person 1 "Joe"
                      [(make-person 2 "Suzy" [])
                       (make-person 3 "Billy" [])
                       (make-person 4 "Rae"
                         [(make-person 5 "Ian"
                            [(make-person 6 "Zoe" [])])])]))
   :ident         [:person/by-id :db/id]}
  (dom/div
    (dom/h4 name)
    (when (seq children)
      (dom/div
        (dom/ul
          (map (fn [p]
                 (ui-person p))
            children))))))

(def ui-person (prim/factory Person {:keyfn :db/id}))

(defsc Root [this {:keys [person-of-interest]}]
  {:initial-state {:person-of-interest {}}
   :query         [{:person-of-interest (prim/get-query Person)}]}
  (dom/div
    (ui-person person-of-interest)))
