(ns cards.defrouter-list-and-editor-cards
  (:require [fulcro.client.dom :as dom]
            [fulcro.client.primitives :as prim :refer [defui]]
            [fulcro.client.dom :as dom]
            [recipes.defrouter-list-and-editor :as ex]
            [fulcro.client.mutations :as m]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.core :as fc]))

(defcard-doc
  "# Routing Between a List and an Editor

   This demo is similar to the `defrouter-for-type-selection` Demo. In fact, the two could be combined to make
   this one polymorphic as well, but the primary interest in this demo is to show swapping between an editor
   and a list (or table).

   This example uses the forms support, with a custom submit and cancel mutation. Other demos and Developer's Guide
   sections talk about form support in more detail, but it is instructive to see how entities could be loaded
   (simulated in the started-callback) as pristine entities and augmented with form support through mutations
   that have a nice clear meaning (to both front and back-end).

   See the comments on the source for more details.

   ```
   (defmutation edit-person
     \"Fulcro mutation: Edit the person with the given ID.\"
     [{:keys [id]}]
     (action [{:keys [state] :as env}]
       (swap! state (fn [s]
                      (-> s
                        ; change the route
                        (r/update-routing-links {:handler :editor :route-params {:id id}})
                        ; make sure the form has form support installed
                        (f/init-form PersonForm (person-ident id)))))))

   (defmutation cancel-edit
     \"Fulcro mutation: Cancel an edit.\"
     [no-args-needed]
     (action [{:keys [state] :as env}]
       (let [ident-of-person-being-edited (r/current-route @state :listform-router)]
         (swap! state (fn [s]
                        (-> s
                          ; change the route
                          (r/update-routing-links {:handler :people})
                          ; clear any edits on the person (this is non-recursive)
                          (update-in ident-of-person-being-edited f/reset-entity)))))))

   (defmutation submit-person
     \"Fulcro mutation: An example of a custom form submit, composing other operations into the submit.\"
     [{:keys [form]}]                                          ; form is passed in from UI to close over it. See dev guide for why.
     (action [{:keys [state]}]
       (let [ident-of-person-being-edited (r/current-route @state :listform-router)]
         (swap! state (fn [s]
                        (-> s
                          ; non-recursive. if using subforms see dev guide
                          (update-in ident-of-person-being-edited f/commit-state)
                          (r/update-routing-links {:handler :people})))))))
   ```
  "
  (dc/mkdn-pprint-source ex/PersonListItem)
  (dc/mkdn-pprint-source ex/ui-person)
  (dc/mkdn-pprint-source ex/PersonList)
  (dc/mkdn-pprint-source ex/PersonForm)
  (dc/mkdn-pprint-source ex/PersonListOrForm)
  (dc/mkdn-pprint-source ex/ui-person-list-or-form)
  (dc/mkdn-pprint-source ex/DemoRoot))

(defcard-fulcro list-and-editor
  ex/DemoRoot
  {}
  {:inspect-data false
   :fulcro       {:started-callback
                  (fn [app]
                    ; simulate a load of people via a simple integration of some tree data
                    (fc/merge-state! app ex/PersonList
                      {:people [
                                (ex/make-person 1 "Tony")
                                (ex/make-person 2 "Sally")
                                (ex/make-person 3 "Allen")
                                (ex/make-person 4 "Luna")]}))}})



