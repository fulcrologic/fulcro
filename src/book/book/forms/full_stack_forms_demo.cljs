(ns book.forms.full-stack-forms-demo
  (:require
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.client.dom :as dom]
    [fulcro.client.routing :as r :refer [defrouter]]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.logging :as log]
    [fulcro.ui.forms :as f]
    [fulcro.client.data-fetch :as df]
    [cljs.reader :refer [read-string]]
    [fulcro.ui.bootstrap3 :as b]
    [fulcro.server :as server :refer [defquery-entity defquery-root]]
    [fulcro.ui.elements :as ele]))

(defn render-example [width height & children]
  (ele/ui-iframe {:frameBorder 0 :height height :width width}
    (apply dom/div #js {:key "example-frame-key"}
      (dom/style nil ".boxed {border: 1px solid black}")
      (dom/link #js {:rel "stylesheet" :href "bootstrap-3.3.7/css/bootstrap.min.css"})
      children)))

(defn make-phone-number [id type num]
  {:db/id id :phone/type type :phone/number num})

(defonce server-state (atom {:all-numbers [(make-phone-number 1 :home "555-1212")
                                           (make-phone-number 2 :home "555-1213")
                                           (make-phone-number 3 :home "555-1214")
                                           (make-phone-number 4 :home "555-1215")]}))

; simulate persisting the data across page reloads
(let [old-state (read-string (str (.getItem js/localStorage "/")))]
  (when (map? old-state)
    (reset! server-state old-state)))

(defn update-phone-number [id incoming-changes]
  (log/info "Server asked to updated phone " id " with changes: " incoming-changes)
  (swap! server-state update-in [:all-numbers (dec id)] merge incoming-changes)
  ;; simulate saving to "disk"
  (.setItem js/localStorage "/" (pr-str @server-state)))

(defquery-root :all-numbers
  (value [env params]
    (js/console.log "query")
    (get @server-state :all-numbers)))

(server/defmutation fulcro.ui.forms/commit-to-entity [p]
  (action [env]
    (let [updates (-> p :form/updates)]
      (doseq [[[table id] changes] updates]
        (case table
          :phone/by-id (update-phone-number id changes)
          (log/info "Server asked to update unknown entity " table))))))

(defn field-with-label
  "A non-library helper function, written by you to help lay out your form."
  ([comp form name label] (field-with-label comp form name label nil))
  ([comp form name label validation-message]
   (dom/div #js {:className (str "form-group" (if (f/invalid? form name) " has-error" ""))}
     (dom/label #js {:className "col-sm-2" :htmlFor name} label)
     ;; THE LIBRARY SUPPLIES f/form-field. Use it to render the actual field
     (dom/div #js {:className "col-sm-10"} (f/form-field comp form name))
     (when (and validation-message (f/invalid? form name))
       (dom/span #js {:className (str "col-sm-offset-2 col-sm-10" name)} validation-message)))))

(defn phone-ident [id-or-props]
  (if (map? id-or-props)
    [:phone/by-id (:db/id id-or-props)]
    [:phone/by-id id-or-props]))

(defsc PhoneForm [this form]
  {:query       [:db/id :phone/type :phone/number f/form-key]
   :ident       (fn [] (phone-ident form))
   :form-fields [(f/id-field :db/id)
                 (f/text-input :phone/number)
                 (f/dropdown-input :phone/type [(f/option :home "Home") (f/option :work "Work")])]}
  (dom/div #js {:className "form-horizontal"}
    ; field-with-label is just a render-helper as covered in basic form documentation
    (field-with-label this form :phone/type "Phone type:")
    (field-with-label this form :phone/number "Number:")))

(def ui-phone-form (prim/factory PhoneForm))

(defn- set-number-to-edit [state-map phone-id]
  (assoc-in state-map [:screen/phone-editor :tab :number-to-edit] (phone-ident phone-id)))

(defn- initialize-form [state-map form-class form-ident]
  (update-in state-map form-ident #(f/build-form form-class %)))

(defmutation edit-phone
  "Mutation: Set up the given phone number to be editable in the
  phone form, and route the UI to the form."
  [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state (fn [state-map]
                   (-> state-map
                     (initialize-form PhoneForm (phone-ident id))
                     (set-number-to-edit id)
                     (r/update-routing-links {:route-params {}
                                              :handler      :route/phone-editor})))))
  (refresh [env] [:main-ui-router]))

(defsc PhoneDisplayRow [this {:keys [db/id phone/type phone/number]}]
  {:query [:ui/fetch-state :db/id :phone/type :phone/number]
   :ident (fn [] (phone-ident id))}
  (b/row {:onClick #(prim/transact! this `[(edit-phone {:id ~id})])}
    (b/col {:xs 2} (name type)) (b/col {:xs 2} number)))

(def ui-phone-row (prim/factory PhoneDisplayRow {:keyfn :db/id}))

(defsc PhoneEditor [this {:keys [number-to-edit]}]
  {; make sure to include the :screen-type so the router can get the ident of this component
   :initial-state {:screen-type :screen/phone-editor}
   :ident         (fn [] [:screen/phone-editor :tab])
   ; NOTE: the query is asking for :number-to-edit.
   ; The edit mutation will fill this in before routing here.
   :query         [f/form-root-key :screen-type {:number-to-edit (prim/get-query PhoneForm)}]}
  (let [; dirty check is recursive and always up-to-date
        not-dirty?  (not (f/dirty? number-to-edit))
        ; validation is tri-state. Most fields are unchecked. Use pure functions to
        ; transform the form to a validated state to check validity of all fields
        valid?      (f/valid? (f/validate-fields number-to-edit))
        not-valid?  (not valid?)
        save        (fn [evt]
                      (when valid?
                        (prim/transact! this
                          `[(f/commit-to-entity {:form ~number-to-edit :remote true})
                            (r/route-to {:handler :route/phone-list})
                            ; ROUTING HAPPENS ELSEWHERE, make sure the UI for that router updates
                            :main-ui-router])))
        cancel-edit (fn [evt]
                      (prim/transact! this
                        `[(f/reset-from-entity {:form-id ~(phone-ident (:db/id number-to-edit))})
                          (r/route-to {:handler :route/phone-list})
                          ; ROUTING HAPPENS ELSEWHERE, make sure the UI for that router updates
                          :main-ui-router]))]
    (dom/div nil
      (dom/h1 nil "Edit Phone Number")
      (when number-to-edit
        (ui-phone-form number-to-edit))
      (b/row {}
        (b/button {:onClick cancel-edit} "Cancel")
        (b/button {:disabled (or not-valid? not-dirty?)
                   :onClick  save} "Save")))))

(defsc PhoneList [this {:keys [phone-numbers]}]
  {:query         [:screen-type {:phone-numbers (prim/get-query PhoneDisplayRow)}]
   :ident         (fn [] [:screen/phone-list :tab])
   ; make sure to include the :screen-type so the router can get the ident of this component
   :initial-state {:screen-type   :screen/phone-list
                   :phone-numbers []}}
  (dom/div nil
    (dom/h1 nil "Phone Numbers (click a row to edit)")
    (b/container nil
      (b/row {} (b/col {:xs 2} "Phone Type") (b/col {:xs 2} "Phone Number"))
      ; Show a loading message while we're waiting for the network load
      (df/lazily-loaded #(mapv ui-phone-row %) phone-numbers))))

(defrouter TopLevelRouter :top-router
  ; Note the ident function works against the router children,
  ; so they must have a :screen-type data field
  (ident [this props] [(:screen-type props) :tab])
  :screen/phone-list PhoneList
  :screen/phone-editor PhoneEditor)

(def ui-top-router (prim/factory TopLevelRouter))

(defsc Root [this {:keys [main-ui-router]}]
  {:query         [{:main-ui-router (prim/get-query TopLevelRouter)}]
   :initial-state (fn [params]
                    ; merge the routing tree into the app state
                    (merge
                      {:main-ui-router (prim/get-initial-state TopLevelRouter {})}
                      (r/routing-tree
                        (r/make-route :route/phone-list
                          [(r/router-instruction :top-router [:screen/phone-list :tab])])
                        (r/make-route :route/phone-editor
                          [(r/router-instruction :top-router [:screen/phone-editor :tab])]))))}
  (render-example "600px" "300px"
    (b/container-fluid nil
      (ui-top-router main-ui-router))))

(defn initialize [app]
  (df/load app :all-numbers PhoneDisplayRow
    {:target  [:screen/phone-list :tab :phone-numbers]
     :refresh [:phone-numbers]}))
