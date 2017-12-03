(ns fulcro-devguide.B-UI-Exercises
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(enable-console-print!)

(defcard-doc

  "# UI exercises

  NOTE - Namespace aliases used in this document:

```clojure
(require '[fulcro.client.primitives :as prim :refer-macros [defui]]
         '[fulcro.client.dom :as dom])
```

  ")

(defn person [{:keys [person/name person/mate]}]
  (dom/li nil
    (dom/input #js {:type "checkbox"})
    name
    (dom/button nil "X")
    (when mate (dom/ul nil (person mate)))))

(defn people-list [people]
  (dom/div nil
    (dom/button nil "Save")
    (dom/button nil "Refresh List")
    (dom/ul nil (map #(person %) people))))

(defn root [state-atom]
  (let [{:keys [last-error people new-person] :as ui-data} @state-atom]
    (dom/div nil
      (dom/div nil (when (not= "" last-error) (str "Error " last-error)))
      (dom/div nil
        (dom/div nil
          (if (= nil people)
            (dom/span nil "Loading...")
            (people-list people))
          (dom/input {:type "text" :value new-person})
          (dom/button nil "Add Person"))))))

(defcard overall-goal
  "## Overall goal

  In the following exercises we'll build a UI using `defui`.
  Once the UI is built, we'll add in a little component local state and callback handling.

  The UI will show a list of people and their partners.

  The overall UI should look as shown below (which is build with plain React dom elements
  and function composition):

  NOTE: We name our component factories with a `ui-` prefix. This makes it much easier for the reader to
  visually distinguish our components from regular functions, and also prevents accidental name clases with
  data. For example, you might have `:person/address` that turns into a local data item called `address`. If your
  React renderer for that was also called `address` you'd have a problem.

  "
  (fn [state-atom _]
    (root state-atom))
  {:last-error "Some error message"
   :new-person ""
   :people     [
                {:db/id 1 :person/name "Joe" :person/mate {:db/id 2 :person/name "Sally"}}
                {:db/id 2 :person/name "Sally" :person/mate {:db/id 1 :person/name "Joe"}}]}
  {:inspect-data false})

(declare ui-person)

(defui Person
  Object
  (initLocalState [this] {})                                ; TODO (ex 3): Add initial local state here

  (render [this]
    ;; TODO: (ex 4) Obtain the 'computed' onDelete handler
    (let [name "What's my :person/name?"                    ; TODO (ex 1): Get the Fulcro properties from this for `name` and `mate`
          mate nil
          checked false]                                    ; TODO (ex 3): Component local state
      (dom/li nil
        (dom/input #js {:type    "checkbox"
                        :onClick (fn [e] (println "TODO ex 3"))
                        :checked false                      ; TODO (ex 3): Modify local state
                        })
        (dom/span nil name)                                 ; TODO (ex 3): Make name bold when checked
        (dom/button nil "X")                                ; TODO (ex 4): Call onDelete handler, if present
        (when mate (dom/ul nil (ui-person mate)))))))

(def ui-person (prim/factory Person))

(defcard exercise-1
  "## Exercise 1 - A UI component

  Create an Fulcro Person UI component. No need to add a query yet. The main
  task is to make sure you understand how to get the properties via
  `prim/props`.

  The template is in this guide file just above this card.

  You've got it right when the following card renders a person and their mate:
  "
  (fn [state-atom _]
    (ui-person @state-atom))
  {:db/id 1 :person/name "Joe" :person/mate {:db/id 2 :person/name "Sally"}}
  {:inspect-data true})

(defui PeopleWidget
  Object
  (render [this]
    ;; TODO (ex 4): Create a deletePerson function
    (let [people []]                                        ; TODO (ex 2): `people` should come from the props
      (dom/div nil
        (if (= nil people)
          (dom/span nil "Loading...")
          (dom/div nil
            (dom/button #js {} "Save")
            (dom/button #js {} "Refresh List")
            ;; TODO (ex 4): Pass deletePerson as the onDelete handler to person element
            (dom/ul nil (map #(ui-person %) people))))))))

(def ui-people (prim/factory PeopleWidget))

(defui Root
  Object
  (render [this]
    (let [widget nil
          new-person nil
          last-error nil]                                   ; TODO (ex 2): Extract the proper props for each var.
      (dom/div nil
        (dom/div nil (when (not= "" last-error) (str "Error " last-error)))
        (dom/div nil
          (ui-people widget)
          (dom/input #js {:type "text"})
          (dom/button #js {} "Add Person"))))))

(def ui-root (prim/factory Root))

(defcard exercise-2
  "## Exercise 2 - A UI tree

  Continue and build out two more components as seen in the source just above this card.

  NOTE: If you look in the
  data below, you'll see our desired UI tree in data form. Use `prim/props` to pull out the
  correct pieces at each level of the rendered UI. When you do this correctly, the
  card should render properly. Be careful around the `:widget` nesting.
  "
  (fn [state-atom _]
    (ui-root @state-atom))
  {:last-error "Some error message"
   :new-person "something typed by the user"
   :widget     {:people [
                         {:db/id 1 :person/name "Joe" :person/mate {:db/id 2 :person/name "Sally"}}
                         {:db/id 2 :person/name "Sally" :person/mate {:db/id 1 :person/name "Joe"}}]}}
  {:inspect-data true})

(defcard exercise-3
  "
  ## Exercise 3 - Component local state

  Components can store local information without using the global app state managed by Fulcro. It is initialized with
  this method in the `Object` section of your `defui`:

  ```
  (initLocalState [this] { map-of-data-to-store })
  ```

  Then use `prim/get-state`, `prim/update-state!`, and `prim/set-state!` to work with the state.

  Add component local state to your Person class, and update the UI so that when
  the person is checked their name becomes bold.

  The proper attributes for the checkbox input are `:checked` and `:onClick`.

  To ensure you got the initial state right make it the default that a person is checked.
  "
  (fn [state-atom _]
    (ui-person @state-atom))
  {:db/id 1 :person/name "Joe"}
  {:inspect-data true})

(defcard exercise-4
  "
  ## Exercise 4 - Computed properties

  In Fulcro, you should not try to pass callbacks directly through props, but should *attach* them instead
  using `prim/computed`.

  ```
  (ui-component-factory (prim/computed props { :computed-thing 4 }))
  ```

  They may be retrieved using `prim/get-computed` on either the `props` or `this`
  passed to render.

  In Fulcro you should manage the modification of lists from the owner
  of the list (because the list is, after all, rendered by that component); however, in our UI the delete button for
  a person is *rendered* in the Person component. Declare a placeholder function in PeopleWidget called `deletePerson`
  that just logs a message to the javascript console (e.g. `(js/console.log \"delete\" p)`,
  where p is the argument passed to the function).

  Pass that function through to each person as `onDelete`, and hook it up to the `X` button.

  Verify it works by checking the console for the messages.
  "
  (fn [state-atom _]
    (ui-root @state-atom))
  {:last-error ""
   :new-person ""
   :widget     {:people [
                         {:db/id 1 :person/name "Joe" :person/mate {:db/id 2 :person/name "Sally"}}
                         {:db/id 2 :person/name "Sally" :person/mate {:db/id 1 :person/name "Joe"}}]}}
  {:inspect-data true})

(defcard-doc
  "Now that you've completed the UI exercises, move on to the [app database](#!/fulcro_devguide.C_App_Database) section.")
