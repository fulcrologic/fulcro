(ns untangled-devguide.O10-Forms-Full-Stack-Demo
  (:require
    [devcards.core :as dc :refer-macros [defcard defcard-doc]]
    [untangled.client.cards :refer [untangled-app]]
    [om.next :as om :refer [defui]]
    [om.dom :as dom]
    [untangled.client.core :as uc]
    [untangled.client.routing :as r :refer [defrouter]]
    [untangled.client.mutations :as m :refer [defmutation]]
    [untangled.client.logging :as log]
    [untangled.client.routing :as r :refer [defrouter]]
    [untangled.ui.forms :as f]
    [untangled.client.data-fetch :as df]
    [cljs.reader :refer [read-string]]
    [untangled.client.network :as un]
    [untangled.ui.bootstrap3 :as b]))

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

; The server queries are handled by returning a map with a :value key, which will be placed in the appropriate
; response format
(defn read-handler [{:keys [state]} k p]
  (log/info "SERVER query for " k)
  (case k
    ; we only have one "server" query...get all of the phone numbers in the database
    :all-numbers {:value (get @state :all-numbers)}
    nil))

;; Server-side mutation handling. We only care about one mutation
(defn write-handler [env k p]
  (log/info "SERVER mutation for " k " with params " p)
  (case k
    `f/commit-to-entity (let [updates (-> p :form/updates)]
                          (doseq [[[table id] changes] updates]
                            (case table
                              :phone/by-id (update-phone-number id changes)
                              (log/info "Server asked to update unknown entity " table))))
    nil))

; Om Next query parser. Calls read/write handlers with keywords from the query
(def server-parser (om/parser {:read read-handler :mutate write-handler}))

; Simulated server. You'd never write this part
(defn server [env tx]
  (server-parser (assoc env :state server-state) tx))

; Networking that pretends to talk to server. You'd never write this part
(defrecord MockNetwork [complete-app]
  un/UntangledNetwork
  (send [this edn ok err]
    ; simulates a network delay:
    (js/setTimeout
      #(let [resp (server {} edn)]
         (ok resp))
      1000))
  (start [this app]
    (assoc this :complete-app app)))



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

(defn phone-ident [id] [:phone/by-id id])

(defui ^:once PhoneForm
  static f/IForm
  (form-spec [this] [(f/id-field :db/id)
                     (f/text-input :phone/number)
                     (f/dropdown-input :phone/type [(f/option :home "Home") (f/option :work "Work")])])
  static om/IQuery
  (query [this] [:db/id :phone/type :phone/number f/form-key])
  static om/Ident
  (ident [this props] (phone-ident (:db/id props)))
  Object
  (render [this]
    (let [form (om/props this)]
      (dom/div #js {:className "form-horizontal"}
        ; field-with-label is just a render-helper as covered in basic form documentation
        (field-with-label this form :phone/type "Phone type:")
        (field-with-label this form :phone/number "Number:")))))

(def ui-phone-form (om/factory PhoneForm))

(defn- set-number-to-edit [state-map phone-id]
  (assoc-in state-map [:screen/phone-editor :tab :number-to-edit] (phone-ident phone-id)))

(defn- initialize-form [state-map form-class form-ident]
  (update-in state-map form-ident #(f/build-form form-class %)))

(defmutation edit-phone
  "Om Mutation: Set up the given phone number to be editable in the
  phone form, and route the UI to the form."
  [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state (fn [state-map]
                   (-> state-map
                     (initialize-form PhoneForm (phone-ident id))
                     (set-number-to-edit id)
                     (r/update-routing-links {:route-params {}
                                              :handler      :route/phone-editor}))))))

(defui ^:once PhoneDisplayRow
  static om/IQuery
  (query [this] [:ui/fetch-state :db/id :phone/type :phone/number])
  static om/Ident
  (ident [this props] [:phone/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [db/id phone/type phone/number]} (om/props this)]
      (b/row {:onClick #(om/transact! this `[(edit-phone {:id ~id})
                                             :ui/react-key])}
        (b/col {:xs 2} (name type)) (b/col {:xs 2} number)))))

(def ui-phone-row (om/factory PhoneDisplayRow {:keyfn :db/id}))

(defui ^:once PhoneEditor
  static uc/InitialAppState
  ; make sure to include the :screen-type so the router can get the ident of this component
  (initial-state [cls params] {:screen-type :screen/phone-editor})
  static om/IQuery
  ; NOTE: the query is asking for :number-to-edit. The edit mutation will fill this in before routing here.
  (query [this] [f/form-root-key :screen-type {:number-to-edit (om/get-query PhoneForm)}])
  Object
  (render [this]
    (let [{:keys [number-to-edit]} (om/props this)
          ; dirty check is recursive and always up-to-date
          not-dirty?  (not (f/dirty? number-to-edit))
          ; validation is tri-state. Most fields are unchecked. Use pure functions to transform the
          ; form to a validated state to check validity of all fields
          valid?      (f/valid? (f/validate-fields number-to-edit))
          not-valid?  (not valid?)
          save        (fn [evt]
                        (when valid?
                          (om/transact! this `[(f/commit-to-entity {:form ~number-to-edit :remote true})
                                               (r/route-to {:handler :route/phone-list})
                                               ; ROUTING HAPPENS ELSEWHERE, make sure the UI for that router updates
                                               :main-ui-router])))
          cancel-edit (fn [evt]
                        (om/transact! this `[(f/reset-from-entity {:form-id ~(phone-ident (:db/id number-to-edit))})
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
                     :onClick  save} "Save"))))))

(defui ^:once PhoneList
  static om/IQuery
  (query [this] [:screen-type {:phone-numbers (om/get-query PhoneDisplayRow)}])
  static uc/InitialAppState
  ; make sure to include the :screen-type so the router can get the ident of this component
  (initial-state [this params] {:screen-type   :screen/phone-list
                                :phone-numbers []})
  Object
  (render [this]
    (let [{:keys [phone-numbers]} (om/props this)]
      (dom/div nil
        (dom/h1 nil "Phone Numbers (click a row to edit)")
        (b/container nil
          (b/row {} (b/col {:xs 2} "Phone Type") (b/col {:xs 2} "Phone Number"))
          ; Show a loading message while we're waiting for the network load
          (df/lazily-loaded #(mapv ui-phone-row %) phone-numbers))))))

(defrouter TopLevelRouter :top-router
  ; Note the ident function works against the router children, so they must have a :screen-type data field
  (ident [this props] [(:screen-type props) :tab])
  :screen/phone-list PhoneList
  :screen/phone-editor PhoneEditor)

(def ui-top-router (om/factory TopLevelRouter))

(defui ^:once Root
  static om/IQuery
  (query [this] [:ui/react-key {:main-ui-router (om/get-query TopLevelRouter)}])
  static uc/InitialAppState
  (initial-state [cls params]
    ; merge the routing tree into the app state
    (merge
      {:main-ui-router (uc/get-initial-state TopLevelRouter {})}
      (r/routing-tree
        (r/make-route :route/phone-list [(r/router-instruction :top-router [:screen/phone-list :tab])])
        (r/make-route :route/phone-editor [(r/router-instruction :top-router [:screen/phone-editor :tab])]))))
  Object
  (render [this]
    (let [{:keys [ui/react-key main-ui-router]} (om/props this)]
      (dom/div #js {:key react-key}
        (ui-top-router main-ui-router)))))

(defcard-doc
  "# Full Stack Form Demo

  This page talks you through a complete full-stack demo of an application that lets you
  both examine data from the server (as a table of values) and click-to-edit those rows
  using the forms support of this library.

  The server is provided by a simulation the has a built-in 1 second delay so that you can watch
  the interactions happen as if the network was very slow. We're using browser local
  storage to make sure the changes get persisted, so you should see your edited
  data on page reloads.

  Note that we're simulating the server using a mock network plugin for the client that
  loops back to 'server-like' code in the browser. As far as the client is concerned,
  it is talking to a real remote, and as far as the remote code goes: it is essentially
  what you could write on a real server to accomplish the same tasks.

  We're implementing the server this way to make it easier to see the complete demo
  without having to run a server.

  This is as simple as creating an implementation of UntangledNetwork that looks like this:

  "
  (dc/mkdn-pprint-source MockNetwork)
  "

  where `server` is pretty much what you have to write on any Untangled Server: an Om parser with
  a read and write handler:

  "
  (dc/mkdn-pprint-source server-parser)
  (dc/mkdn-pprint-source server)
  "

  ## Application Load

  When the application loads it uses `data-fetch/load` to query the server for
  `:all-numbers`.

  ```
  (df/load app :all-numbers PhoneDisplayRow {:target [:screen/phone-list :tab :phone-numbers]})
  ```

  We have a very simple database that looks like this on the server:
  "
  (dc/mkdn-pprint-source server-state)
  "

  So we can implement our server read emitter for the server parser very simply as:

  "
  (dc/mkdn-pprint-source read-handler)
  "
  where we set up the parser environment to have the above server state atom as just `state`.

  # The UI

  We're using a UI router via defrouter to create two screens: A phone list and phone editor screen.

  The basic UI tree looks like this:

  ```
             Root
               |
         TopLevelRouter
           /          \\
    PhoneEditor     PhoneList
        |               / | \\
    PhoneForm       PhoneDisplayRow...
  ```

  The UI starts out showing PhoneList. Clicking on an element leads to editing.

  The code of the various elements looks like this:

  "
  (dc/mkdn-pprint-source phone-ident)
  (dc/mkdn-pprint-source PhoneDisplayRow)
  (dc/mkdn-pprint-source PhoneList)
  (dc/mkdn-pprint-source PhoneEditor)
  (dc/mkdn-pprint-source TopLevelRouter)
  (dc/mkdn-pprint-source Root)
  "

  # The Mutations

  Note: PhoneForm and PhoneDisplayRow share the same ident since they render two differing views
  of the same entity in our database.

  ## Editing

  Since the phone numbers were loaded from raw data on a server, they are not form capable yet.

  Thus, the application must do a few things in order for editing to work:

  - It must add form state to the entity using `build-form`. We create a quick helper function
  to do this against app state (the function `phone-ident` just returns the ident of a phone
  number based on the simple ID):

  "
  (dc/mkdn-pprint-source initialize-form)
  "
  - The form itself needs to link up to the thing it should edit. In order words we need to write
  an ident into PhoneEditor to point it to the (newly initialized) PhoneForm instance. We write
  another helper to do this against app state (as a map). Note the path is just the ident of the
  PhoneEditor combined with the field name.
  "
  (dc/mkdn-pprint-source set-number-to-edit)
  "
  - Tell the top UI router to change UI routes. We can do this with the built-in `route-to` mutation.

  Our final `edit-phone` mutation is thus:

  ```
  (defmutation edit-phone
    [{:keys [id]}]
    (action [{:keys [state]}]
      (swap! state (fn [state-map]
                     (-> state-map
                       (initialize-form PhoneForm (phone-ident id))
                       (set-number-to-edit id)
                       (r/update-routing-links {:route-params {}
                                                :handler      :route/phone-editor}))))))
  ```

  ## Working with the Form Data - Global Properties

  Some fields will cause validation to run on the form as they are used, but in general you want to know
  where the overall form (and subforms) stand. The `dirty?` check is always correct, since editing
  a field causes an immediate update to this flag. Validation is not so easy. We don't want to
  validate things that have not yet been reached (which might show error messages are required fields
  the user has yet to reach). There is a mutation to run validation on the entire form, but this
  is only what you want if you're trying to update the UI on the entire form to show validation
  problems.

  Instead, you will often treat the incoming form props as what they are: the current state of the form.
  The `validate-fields` function is a pure function that takes a form and recursively runs validations on it,
  returning a new version of the form that is marked. This has no effect on the UI, because you're doing this
  in isolation from the UI. These are pure functions. No mutation is taking place. The result can then
  be passed to `valid?` which will give you a boolean answer. You can then use this to do things like
  make the Save button disable until they've finished working on the form.

  ## Commit and Reset

  Commit and reset are a built-in mutations.  They both have cljs and Om-composable version (the former
  just calls transact for you). Often you'll want to combine other operations with a commit or
  reset, as is shown in the form editor.

  NOTE: Since our editor is asking for routing somewhere deep below the routing component
  it must use follow-on reads (in this case on the :main-ui-router key) to ensure that the UI updates properly.

  The Save button runs a commit operation with a remote flag. This causes the changes to not only be sync'd with
  the form's pristine state, it also causes a network (in this case simulated) request to have the server update
  its copy.

  See the form documentation for the full possible items in such a request. For this example we'll describe
  just the one we're supporting: Updates.

  The parameters passed to the server on update have
  a `:form/updates` key with a map whose keys are the idents of things that changed, and whose values are maps
  of the field/value updates. For example:

  ```
  {:form/updates {[:phone/by-id 1] {:phone/number \"444-5421\"}}}
  ```

  would be sent to indicate that phone number with id 1 had just its `:phone/number` attribute changed to the
  new value \"444-5421\".

  So, a really naive implementation of this update handler looks like this:
  "
  (dc/mkdn-pprint-source update-phone-number)
  (dc/mkdn-pprint-source write-handler)
  "

  # The Final Result

  The server state and application are in the following two live cards:
  ")

(defcard server-state-card
  "# This card shows the current server-side state (simulated)"
  (fn [state-atom _]
    (dom/div nil ""))
  server-state
  {:inspect-data true})

(defcard
  "# The Application

  You can enable data inspection on this card to see the client state as you work on the form.
  "
  (untangled-app Root
    :networking (map->MockNetwork {})
    :started-callback (fn [{:keys [reconciler] :as app}]
                        (df/load app :all-numbers PhoneDisplayRow {:target  [:screen/phone-list :tab :phone-numbers]
                                                                   :refresh [:screen-type]})))
  {}
  {:inspect-data false})

