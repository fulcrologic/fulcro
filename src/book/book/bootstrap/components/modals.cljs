(ns book.bootstrap.components.modals
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [fulcro.client.routing :as routing :refer [defrouter]]
            [book.bootstrap.helpers :refer [render-example sample]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.ui.bootstrap3 :as b]))

(defn person-ident
  "Returns an ident. Accepts either the full entity (props) or just an ID. Returns an ident for a person."
  [id-or-props]
  (if (map? id-or-props)
    [:person/by-id (:db/id id-or-props)]
    [:person/by-id id-or-props]))

(defsc DemoPerson [this {:keys [db/id person/name]}]
  {
   :ident         (fn [] (person-ident id))
   :query         [:db/id :person/name]
   :initial-state (fn [{:keys [id name]}] {:db/id id :person/name name})}
  ;; Just a simple component to display a person
  (b/container nil
    (b/row nil
      (b/col {:xs 4} "Name: ")
      (b/col {:xs 4} name))))

(def ui-demoperson (prim/factory DemoPerson))

(defsc PersonEditor [this {:keys [db/id person/name ui/edited-name]}]
  {; share the ident of a person, so we overlay the editor state on a person entity
   :ident (fn [] (person-ident id))
   ; :ui/edited-name is a temporary place to put what the user is typing in the editor. saving will copy this to :person/name
   :query [:db/id :person/name :ui/edited-name]}
  (b/container nil
    (b/row nil
      (b/col {:xs 4} "Name: ")
      (b/col {:xs 4} (dom/input #js {:value    edited-name
                                     ; leverage helper transact that uses our ident to update data
                                     :onChange #(m/set-string! this :ui/edited-name :event %)})))))

(def ui-person-editor (prim/factory PersonEditor {:keyfn :db/id}))

(defn copy-edit-to-name*
  "Copy the current name of the person to the :ui/edited-name field for modal editing. This allows them to cancel
  the edit, or save it."
  [person]
  (assoc person :person/name (:ui/edited-name person)))

(defmutation save-person
  "mutation: Save a person. Takes the entire person entity as :person"
  [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in (person-ident id) copy-edit-to-name*)))

(defn set-person-to-edit*
  "Point the edit modal's :person-editor field at the correct person to edit. This must be done before showing the
  dialog or the editor won't have state."
  [state id]
  (assoc-in state [:edit-modal :singleton :person-editor] [:person/by-id id]))

(defn initialize-edited-name*
  "Copy the current value of the person's name into the :ui/edited-name field, so the editor can edit a copy instead
  of the original."
  [state id]
  (update-in state (person-ident id) (fn [person] (assoc person :ui/edited-name (:person/name person)))))

(defmutation edit-person
  "mutation: Start editing a person."
  [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state (fn [s] (-> s
                           (set-person-to-edit* id)
                           (initialize-edited-name* id))))))

(defsc PersonModal [this {:keys [person-editor modal]}]
  {; We're storing both the real modal and the person-editor's state in this custom modal (which combines the two).
   ; The person-editor field will eventually need to point to the person to edit (by ident in the normalized db)
   ; When we get to routing, the :id of the modal will be what we use as the type of thing to route to...
   :initial-state (fn [p] {:person-editor nil :modal (prim/get-initial-state b/Modal {:id :edit-modal :backdrop true :keyboard false})})
   ; ident will come from UI router
   :query         [{:person-editor (prim/get-query PersonEditor)} {:modal (prim/get-query b/Modal)}]}
  ; The modal container
  (b/ui-modal modal
    (b/ui-modal-title nil
      (dom/b #js {:key "title"} "Person Editor"))
    (b/ui-modal-body nil
      ; NOTE: The person editor is embedded into the body. This gives us parallel data model of two concepts that
      ; have more of a tree relation in the UI.
      (ui-person-editor person-editor))
    (b/ui-modal-footer nil
      (b/button {:key "cancel" :onClick #(prim/transact! this `[(b/hide-modal {:id :edit-modal})])} "Cancel")
      ; Save can be implemented with respect to the person data. Note, though, that the person-editor
      ; itself may be a stale copy (since the editor can refresh without re-rendering the modal)
      ; Thus, we use the ID from the person editor, which is stable in the context of an editing pop.
      (b/button {:key "save" :onClick #(prim/transact! this `[(save-person {:id ~(:db/id person-editor)})
                                                              (b/hide-modal {:id :edit-modal})
                                                              :person/name]) :kind :primary} "Save"))))

(defsc WarningModal [this {:keys [message modal]}]
  {
   ; NOTE: When we get to routing, the :id of the modal will be what we use as the type of thing to route to...
   :initial-state (fn [p] {:message "Stuff broke" :modal (prim/get-initial-state b/Modal {:id :warning-modal :backdrop true})})
   ; ident will come from UI router
   :query         [:message {:modal (prim/get-query b/Modal)}]} ; so a mutation can change the message, in this case.
  (b/ui-modal modal
    (b/ui-modal-title nil
      (dom/b #js {:key "warning"} " WARNING!"))
    (b/ui-modal-body nil
      (dom/p #js {:key "message" :className b/text-danger} message))
    (b/ui-modal-footer nil
      (b/button {:key "ok-button" :onClick #(prim/transact! this `[(b/hide-modal {:id :warning-modal})])} "Bummer!"))))

(def ui-warning-modal (prim/factory WarningModal {:keyfn :id}))

(defrouter ModalRouter :modal-router
  ; REMEMBER: The ident determines the type of thing to render (the first element has to be the same
  ; as one of the keywords below). We're treating the id of the modal as the type, since they are singletons,
  ; and the two IDs are :warning-modal and :edit-modal. This means the ident function MUST place one of those
  ; keywords as the first element of the ident it returns.
  (ident [this props] [(-> props :modal :db/id) :singleton])
  :warning-modal WarningModal
  :edit-modal PersonModal)

(def ui-modal-router (prim/factory ModalRouter))

(defn start-person-editor
  "Run a transaction that does all of the steps to edit a given person."
  [comp person-id]
  (prim/transact! comp `[(routing/route-to {:handler :edit}) ; :edit is a route in the routing tree
                         (edit-person {:id ~person-id})
                         (b/show-modal {:id :edit-modal})
                         ; follow-on read ensures re-render at root
                         :modal-router]))

(defn show-warning
  "Run a transaction that does all of the steps to show a warning modal."
  [comp]
  (prim/transact! comp `[(routing/route-to {:handler :warning}) ; :warning is a route in the routing tree
                         (b/show-modal {:id :warning-modal})
                         ; follow-on read ensures re-render at root
                         :modal-router]))

(defsc ModalRoot [this {:keys [:ui/react-key person modal-router]}]
  {:initial-state (fn [p] (merge
                            ; make a routing tree for the two modals and merge it in the app state
                            (routing/routing-tree
                              (routing/make-route :edit [(routing/router-instruction :modal-router [:edit-modal :singleton])])
                              (routing/make-route :warning [(routing/router-instruction :modal-router [:warning-modal :singleton])]))
                            ; general initial state
                            {:person       (prim/get-initial-state DemoPerson {:id 1 :name "Sam"})
                             :modal-router (prim/get-initial-state ModalRouter {})}))
   :query         [:ui/react-key {:person (prim/get-query DemoPerson)} {:modal-router (prim/get-query ModalRouter)}]}
  (render-example "100%" "500px"
    (dom/div #js {:key react-key}
      ; show the current value of the person
      (ui-demoperson person)
      (b/button {:onClick #(start-person-editor this (:db/id person))} "Edit Person")
      (b/button {:onClick #(show-warning this)} "Show Warning")
      ; let the router render just the modal we need
      (ui-modal-router modal-router))))


