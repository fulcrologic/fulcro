(ns recipes.defrouter-list-and-editor
  (:require
    [fulcro.client.dom :as dom]
    [fulcro.client.routing :as r :refer [defrouter]]
    [fulcro.client.primitives :as prim :refer [defui]]
    [fulcro.client.primitives :as prim+]
    [fulcro.client.core :as fc]
    [fulcro.client.mutations :refer [defmutation]]
    [fulcro.ui.bootstrap3 :as b]
    [fulcro.ui.elements :as ele]
    [fulcro.ui.forms :as f]))

(defn person-ident
  "Generate an ident from a person."
  [id-or-props]
  (if (map? id-or-props)
    (person-ident (:db/id id-or-props))
    [:person/by-id id-or-props]))

(declare PersonForm)

(defmutation edit-person
  "Fulcro mutation: Edit the person with the given ID."
  [{:keys [id]}]
  (action [{:keys [state] :as env}]
    (swap! state (fn [s]
                   (-> s
                     ; change the route
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

(defui ^:once PersonForm
  static prim/Ident
  (ident [this props] (person-ident props))
  static prim/IQuery
  (query [this] [:db/id :person/name f/form-key f/form-root-key])
  static f/IForm
  (form-spec [this] [(f/id-field :db/id)
                     (f/text-input :person/name)])
  Object
  (render [this]
    (let [{:keys [db/id person/name] :as form-props} (prim/props this)]
      (b/form-horizontal nil
        (b/labeled-input {:split           4
                          :input-generator (fn [_] (f/form-field this form-props :person/name))} "Name:")
        (b/labeled-input {:split           4
                          :input-generator (fn [_]
                                             (dom/div nil
                                               ; the follow-on read of :root/router ensures re-render from the router level
                                               (b/button {:onClick #(om+/transact! this `[(cancel-edit {}) :root/router])} "Cancel")
                                               (b/button {:onClick #(om+/transact! this `[(submit-person {:form form-props}) :root/router])} "Save")))} "")))))

(defui ^:once PersonListItem
  static prim/Ident
  (ident [this props] (person-ident props))
  static prim/IQuery
  (query [this] [:db/id :person/name])
  Object
  (render [this]
    (let [{:keys [db/id person/name] :as props} (prim/props this)
          onSelect (prim/get-computed this :onSelect)]
      ; the follow-on read of :root/router ensures re-render from the router level
      (dom/li #js {:onClick #(om+/transact! this `[(edit-person {:id ~id}) :root/router])}
        (dom/a #js {:href "javascript:void(0)"} name)))))

(def ui-person (om+/factory PersonListItem {:keyfn :db/id}))

(def person-list-ident [:person-list/table :singleton])

(defui ^:once PersonList
  static fc/InitialAppState
  (initial-state [c p] {:people []})
  static prim/Ident
  (ident [this props] person-list-ident)
  static prim/IQuery
  (query [this] [{:people (om+/get-query PersonListItem)}])
  Object
  (render [this]
    (let [{:keys [people]} (prim/props this)
          onSelect (prim/get-computed this :onSelect)]
      (dom/div nil
        (dom/h4 nil "People")
        (dom/ul nil
          (map (fn [i] (ui-person (prim/computed i {:onSelect onSelect}))) people))))))

(defrouter PersonListOrForm :listform-router
  (ident [this props]
    (if (contains? props :people)
      person-list-ident
      (person-ident props)))
  ; if the router points to a person list entity, render with a PersonList. This is the default route.
  :person-list/table PersonList
  ; if the router points to a person entity, render with PersonForm
  :person/by-id PersonForm)

(def ui-person-list-or-form (om+/factory PersonListOrForm))

(defui ^:once DemoRoot
  static prim/IQuery
  (query [this] [:ui/react-key {:root/router (om+/get-query PersonListOrForm)}])
  static fc/InitialAppState
  (initial-state [c p] (merge
                         (r/routing-tree
                           (r/make-route :people [(r/router-instruction :listform-router person-list-ident)])
                           ; configure the editor route to be able to point to any person via a route parameter
                           (r/make-route :editor [(r/router-instruction :listform-router (person-ident :param/id))]))
                         {:root/router (fc/get-initial-state PersonListOrForm nil)}))
  Object
  (render [this]
    (let [{:keys [ui/react-key root/router]} (prim/props this)]
      ; devcards, embed in iframe so we can use bootstrap css easily
      (ele/ui-iframe {:frameBorder 0 :height "300px" :width "100%"}
        (dom/div #js {:key react-key}
          (dom/style nil ".boxed {border: 1px solid black}")
          (dom/link #js {:rel "stylesheet" :href "bootstrap-3.3.7/css/bootstrap.min.css"})
          (b/container-fluid {}
            (ui-person-list-or-form router)))))))
