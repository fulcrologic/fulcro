(ns book.queries.union-example-2
  (:require [fulcro.client.dom :as dom]
            [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client :as fc]
            [fulcro.client.routing :as r :refer [defrouter]]
            [fulcro.client.mutations :refer [defmutation]]
            [book.macros :refer [defexample]]
            [fulcro.ui.bootstrap3 :as b]
            [fulcro.ui.elements :as ele]
            [fulcro.ui.forms :as f]
            [fulcro.client.logging :as log]))

(defn person-ident
  "Generate an ident from a person."
  [id-or-props]
  (if (map? id-or-props)
    [:person/by-id (:db/id id-or-props)]
    [:person/by-id id-or-props]))

(declare PersonForm)

(defmutation edit-person
  "Fulcro mutation: Edit the person with the given ID."
  [{:keys [id]}]
  (action [{:keys [state] :as env}]
    (swap! state (fn [s]
                   (-> s
                     ; change the route. This just points the routers via ident changes
                     (r/update-routing-links {:handler :editor :route-params {:id id}})
                     ; make sure the form has form support installed
                     (f/init-form PersonForm (person-ident id)))))))

(defmutation cancel-edit
  "Fulcro mutation: Cancel an edit."
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
  "Fulcro mutation: An example of a custom form submit, composing other operations into the submit."
  [{:keys [form]}]                                          ; form is passed in from UI to close over it. See dev guide for why.
  (action [{:keys [state]}]
    (let [ident-of-person-being-edited (r/current-route @state :listform-router)]
      (swap! state (fn [s]
                     (-> s
                       ; non-recursive. if using subforms see dev guide
                       (update-in ident-of-person-being-edited f/commit-state)
                       (r/update-routing-links {:handler :people})))))))


(defn make-person [id n] {:db/id id :person/name n})

(defsc PersonForm [this {:keys [db/id person/name] :as form-props}]
  {:ident     (fn [] (person-ident form-props))
   :query     [:db/id :person/name f/form-key f/form-root-key]
   :protocols [static f/IForm
               (form-spec [this] [(f/id-field :db/id)
                                  (f/text-input :person/name)])]}
  (b/form-horizontal nil
    (b/labeled-input {:split           4
                      :input-generator (fn [_] (f/form-field this form-props :person/name))} "Name:")
    (b/labeled-input {:split           4
                      :input-generator (fn [_]
                                         (dom/div nil
                                           ; the follow-on read of :root/router ensures re-render from the router level
                                           (b/button {:onClick #(prim/transact! this `[(cancel-edit {}) :root/router])} "Cancel")
                                           (b/button {:onClick #(prim/transact! this `[(submit-person {:form form-props}) :root/router])} "Save")))} "")))

(defsc PersonListItem [this {:keys [db/id person/name] :as props}]
  {:ident (fn [] (person-ident props))
   :query [:db/id :person/name]}
  ; the follow-on read of :root/router ensures re-render from the router level
  (dom/li #js {:onClick #(prim/transact! this `[(edit-person {:id ~id}) :root/router])}
    (dom/a #js {:href "javascript:void(0)"} name)))

(def ui-person (prim/factory PersonListItem {:keyfn :db/id}))

(def person-list-ident [:person-list/table :singleton])

(defsc PersonList [this {:keys [people]}]
  {:initial-state (fn [p] {:people []})
   :query         [{:people (prim/get-query PersonListItem)}]
   :ident         (fn [] person-list-ident)}
  (dom/div nil
    (dom/h4 nil "People")
    (dom/ul nil
      (map (fn [i] (ui-person i)) people))))

(defrouter PersonListOrForm :listform-router
  (ident [this props]
    (if (contains? props :people)
      person-list-ident
      (person-ident props)))
  ; if the router points to a person list entity, render with a PersonList. This is the default route.
  :person-list/table PersonList
  ; if the router points to a person entity, render with PersonForm
  :person/by-id PersonForm)

(def ui-person-list-or-form (prim/factory PersonListOrForm))

(defsc Root [this {:keys [ui/react-key root/router] :as props}]
  {:query         [:ui/react-key {:root/router (prim/get-query PersonListOrForm)}]
   :initial-state (fn [p] (merge
                            ; This data is used by the `update-routing-links` functions to switch routes (union idents on the router's current route)
                            (r/routing-tree
                              ; switch to the person list
                              (r/make-route :people [(r/router-instruction :listform-router person-list-ident)])
                              ; switch to the given person (id is passed to update-routing-links and become :param/id)
                              (r/make-route :editor [(r/router-instruction :listform-router (person-ident :param/id))]))
                            {:root/router (prim/get-initial-state PersonListOrForm nil)}))}
  ; embed in iframe so we can use bootstrap css easily
  (ele/ui-iframe {:frameBorder 0 :height "300px" :width "100%"}
    (dom/div #js {:key react-key}
      (dom/style nil ".boxed {border: 1px solid black}")
      (dom/link #js {:rel "stylesheet" :href "bootstrap-3.3.7/css/bootstrap.min.css"})
      (b/container-fluid {}
        (ui-person-list-or-form router)))))

(defexample "Unions as View/Edit Routers" Root "union-example-2"
  :started-callback (fn [{:keys [reconciler]}]
                      ; simulate a load of people via a simple integration of some tree data
                      (prim/merge-component! reconciler PersonList {:people [(make-person 1 "Tony")
                                                                             (make-person 2 "Sally")
                                                                             (make-person 3 "Allen")
                                                                             (make-person 4 "Luna")]})))
