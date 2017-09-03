(ns cards.convenience-macro-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [fulcro.client.core :as fc :refer [defsc]]
    [om.dom :as dom]
    [om.next :as om :refer [defui]]))

(defsc Person
  "A person component"
  [this {:keys [db/id person/name]} computed children]
  {:table         :PERSON/by-id
   :props         [:db/id :person/name]
   :initial-state {:db/id :param/id :person/name :param/name}}
  (dom/div nil
    name))

(defui ^:once GeneratedPerson
  ; a :param/name turns into (:name params)
  static fc/InitialAppState
  (initial-state [c params] {:db/id (:id params) :person/name (:name params)})
  ; combo of :table and :id (defaults to db/id) options. Not generated if :table is missing
  static om/Ident
  (ident [this props] [:PERSON/by-id (:db/id props)])
  ; combo of :props and :children (as joins)
  static om/IQuery
  (query [this] [:db/id :person/name])
  Object
  (render [this]
    ; Argument list is morphed into a `let`
    (let [{:keys [db/id person/name]} (om/props this)
          computed (om/get-computed this)
          children (om/children this)]
      (dom/div nil name))))

(def ui-person (om/factory Person {:keyfn :db/id}))

(defsc Root
  [this {:keys [people ui/react-key]} _ _]
  {:props         [:ui/react-key]
   :initial-state {:people [{:id 1 :name "Tony"} {:id 2 :name "Sam"}
                            {:id 3 :name "Sally"}]}
   :children      {:people Person}}
  (dom/div #js {:key react-key}
    (mapv ui-person people)))

(defui ^:once GeneratedRoot
  static fc/InitialAppState
  (initial-state [c params] {:people [(fc/get-initial-state Person {:id 1 :name "Tony"})
                                      (fc/get-initial-state Person {:id 2 :name "Sam"})
                                      (fc/get-initial-state Person {:id 3 :name "Sally"})]})
  static om/IQuery
  (query [this] [:ui/react-key {:people (om/get-query Person)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key people]} (om/props this)
          _ (om/get-computed this)
          _ (om/children this)]
      (mapv ui-person people))))

(dc/defcard-doc "# The defsc Macro

  Fulcro includes a macro, `defsc`, that can build an syntax-checked component with the most common elements:
  ident (optional), query, render, and initial state (optional). The syntax checking prevents a lot of the most
  common errors when writing a component, and the concise syntax reduces boilerplate to the essential novelty.

  The parameter list can by used to do full Clojure destructuring on the props, computed, and children without
  having to write a separate `let`.

  For example, one could specify `Person` like so:"
  (dc/mkdn-pprint-source Person)
  "And the resulting generated person would look like this (but would have the name `Person`):"
  (dc/mkdn-pprint-source GeneratedPerson)
  "A root component (with no ident, but with query children) might look like this:"
  (dc/mkdn-pprint-source Root)
  "And the resulting generated root would look like this (but would have the name `Root`):"
  (dc/mkdn-pprint-source GeneratedRoot)
  "

  Feel free to edit the components in this source file and try out the syntax checking. For example, try:

  - Mismatching the name of a prop in options with a destructured name in props.
  - Destructuring a prop that isn't in props or children
  - Including initial state for a field that is not listed as a prop or child in options.
  - Using a scalar value for the initial value of a child (instead of a map or vector of maps)
  - Forget to query for the ID field of a component that is stored in a table (ident)

  See the docstring on the macro (or the source of this card file) for more details.")

(defcard-fulcro demo-card
  Root)

