(ns untangled-devguide.E-UI-Queries-and-State-Exercises
  (:require-macros [cljs.test :refer [is]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

; TODO: These need to be a LOT better, but cannot rely on knowledge of
; client yet.

; NOTE: The TODO items below are notes to the person DOING the exercies!

(declare om-person)

(defui Person
  ;; TODO: Add a query for :db/id, :person/name, and a recursive access of :person/mate
  ;; TODO: Add an ident that uses :db/id
  Object
  (initLocalState [this] {:checked false})
  (render [this]
    (let [{:keys [person/name person/mate]} (om/props this)
          {:keys [onDelete]} (om/get-computed this)
          checked (om/get-state this :checked)]
      (dom/li nil
        (dom/input #js {:type    "checkbox"
                        :onClick #(om/update-state! this update :checked not)
                        :checked (om/get-state this :checked)})
        (if checked
          (dom/b nil name)
          (dom/span nil name))
        (when onDelete
          (dom/button #js {:onClick #(onDelete name)} "X"))
        (when mate (dom/ul nil (om-person mate)))))))

(def om-person (om/factory Person {:keyfn :db/id}))

(defui PeopleWidget
  Object
  (render [this]
    (let [people (-> (om/props this) :people)
          deletePerson (fn [p] (println "Delete" p))]
      (dom/div nil
        (if (= nil people)
          (dom/span nil "Loading...")
          (dom/div nil
            (dom/button #js {} "Save")
            (dom/button #js {} "Refresh List")
            (dom/ul nil (map #(om-person (om/computed % {:onDelete deletePerson})) people))))))))

(def people-widget (om/factory PeopleWidget))

(defui Root
  ;; TODO: Add root query. Remember to include top-level properties and compose in PeopleWidget
  Object
  (render [this]
    (let [{:keys [widget new-person last-error]} (om/props this)]
      (dom/div nil
        (dom/div nil (when (not= "" last-error) (str "Error " last-error)))
        (dom/div nil
          (people-widget widget)
          (dom/input #js {:type "text"})
          (dom/button #js {} "Add Person"))))))

(def om-root (om/factory Root))

(defcard-doc
  "# Query exercises

  In this section you learned about queries. In the following exercises we'll continue
  working on our components from the UI Exercises section. The suggested solution to
  the final exercise there is at the top of the source for this namespace.

  ")

(def db {:last-error ""
         :new-person ""
         :db/id      {1 {:db/id 1 :person/name "Joe" :person/mate [:db/id 2]}
                      2 {:db/id 2 :person/name "Sally" :person/mate [:db/id 1]}}
         :widget     {:people [[:db/id 1] [:db/id 2]]}})

(defcard exercise-1
  "## Exercise 1

TODO: Split this into smaller bits.

  OK, you've still got a plain React component. `defui` just makes a React class.

  This exercise is about connecting the dots. The rules for co-located queries are
  summarized here:

  - The interfaces for IQuery etc are declared `static`. This is not a cljs keyword, it is
  an invention of the macro. It causes the macro to place those things on the generated React class.
  - A `defui` component need not have a query. These are known as stateless components.
  - If a component *has* a query, it must be its own. Do not embed a query of another component as the full query
  of another (you must at least use a join if this is what you want to do).

  Finish building out the suggested goal interface using Om components with co-located queries.

  Remember that you will now have to obtain your properties from `this` using `om/props`.

  This file (just above this card's source) contains starter code. When correct, this card should render
  correctly. Note that this card does not detect the error of using another component's query as
  the full query.
  "
  (fn [state-atom _]
    (om-root (om/db->tree (om/get-query Root) db db)))
  {:new-person "", :last-error "",
   :widget     {:people [{:person/name "Joe" :db/id 1 :person/mate {:person/name "Sally" :db/id 2}}
                         {:person/name "Sally" :db/id 2 :person/mate {:person/name "Joe" :db/id 1}}]}}
  {:inspect-data true})

(defcard-doc
  "Now that we've got the basics, let's put it together in [building an Untangled client](#!/untangled_devguide.F_Untangled_Client).")
