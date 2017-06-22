(ns untangled-devguide.N15-Twitter-Bootstrap-Components
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [untangled.client.routing :as routing :refer [defrouter]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc deftest]]
            [cljs.reader :as r]
            [om.next.impl.parser :as p]
            [untangled-devguide.N10-Twitter-Bootstrap-CSS :refer [render-example sample]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [untangled.ui.elements :as ele]
            [untangled.client.cards :refer [untangled-app]]
            [untangled.client.mutations :as m :refer [defmutation]]
            [untangled.ui.bootstrap3 :as b]
            [untangled.client.core :as uc]
            [devcards.core :as dc]))

(defui DropdownRoot
  static uc/InitialAppState
  (initial-state [this props] {:dropdown   (b/dropdown :file "File" [(b/dropdown-item :open "Open")
                                                                     (b/dropdown-item :close "Close")
                                                                     (b/dropdown-divider :divider-1)
                                                                     (b/dropdown-item :quit "Quit")])
                               :dropdown-2 (b/dropdown :select "Select One" [(b/dropdown-item :a "A")
                                                                             (b/dropdown-item :b "B")])})
  static om/IQuery
  (query [this] [{:dropdown (om/get-query b/Dropdown)} {:dropdown-2 (om/get-query b/Dropdown)}])
  Object
  (render [this]
    (let [{:keys [dropdown dropdown-2]} (om/props this)]
      (render-example "100%" "150px"
        (let [select (fn [id] (js/alert (str "Selected: " id)))]
          (dom/div #js {:height "100%" :onClick #(om/transact! this `[(b/close-all-dropdowns {})])}
            (b/ui-dropdown dropdown :onSelect select :kind :success)
            (b/ui-dropdown dropdown-2 :onSelect select :kind :success :stateful? true)))))))

(defcard-doc
  "
  # Active Dropdown Component

  Active dropdowns are Om components with state.

  - `dropdown` - a function that creates a dropdown's state
  - `dropdown-item` - a function that creates an items with a label
  - `ui-dropdown` - renders the dropdown. It requires the dropdown's properties, and allows optional named arguments:
     - `:onSelect` the callback for selection. It is given the selected element's id
     - `:kind` Identical to the `button` `:kind` attribute.

  All labels are run through `tr-unsafe`, so if a translation for the current locale exists it will be used.

  The following Om mutations are are available:

  Close (or open) a specific dropdown by ID:
  ```
  (om/transact! component `[(b/set-dropdown-open {:id :dropdown :open? false})]`)
  ```

  Close dropdowns (globally). Useful for capturing clicks a the root to close dropdowns when the user clicks outside of
  an open dropdown.

  ```
  (om/transact! component `[(b/close-all-dropdowns {})])
  ```

  You can set highlighting on an item in the menu (to mark it as active) with:

  ```
  (om/transact! component `[(b/set-dropdown-item-active {:id :item-id :active? true})])
  ```

  An example usage:

  ```
  (defui DropdownRoot
    static uc/InitialAppState
    (initial-state [this props] {:dropdown (b/dropdown :file \"File\" [(b/dropdown-item :open \"Open\")
                                                                     (b/dropdown-item :close \"Close\")
                                                                     (b/dropdown-divider)
                                                                     (b/dropdown-item :quit \"Quit\")])})
    static om/IQuery
    (query [this] [{:dropdown (om/get-query b/Dropdown)}])
    Object
    (render [this]
      (let [{:keys [dropdown]} (om/props this)]
        (b/ui-dropdown dropdown :kind :success :onSelect (fn [id] (js/alert (str \"Selected: \" id)))))))
  ```

  generates the dropdown in the card below.
  ")

(defcard dropdown (untangled-app DropdownRoot) {} {:inspect-data false})

(m/defmutation nav-to [{:keys [page]}]
  (action [{:keys [state]}] (swap! state assoc :current-page page)))

(defui NavRoot
  static uc/InitialAppState
  (initial-state [this props] {:current-page :home
                               ; Embed the nav in app state as a child of this component
                               :nav          (b/nav :main-nav :tabs :normal
                                               :home
                                               [(b/nav-link :home "Home" false)
                                                (b/nav-link :other "Other" false)
                                                (b/dropdown :reports "Reports"
                                                  [(b/dropdown-item :report-1 "Report 1")
                                                   (b/dropdown-item :report-2 "Report 2")])])})
  static om/IQuery
  ; make sure to add the join on the same keyword (:nav)
  (query [this] [:current-page {:nav (om/get-query b/Nav)}])
  Object
  (render [this]
    (let [{:keys [nav current-page]} (om/props this)]       ; pull the props for nav
      (render-example "100%" "150px"
        (b/container-fluid {}
          (b/row {}
            (b/col {:xs 12}
              ; render it, use onSelect to be notified when nav changes. Note: `nav-to` is just part of this demo.
              (b/ui-nav nav :onSelect (fn [id] (om/transact! this `[(nav-to ~{:page id})])))))
          (b/row {}
            (b/col {:xs 12}
              (dom/p #js {} (str "Current page: " current-page)))))))))

(defcard-doc
  "# Nav Elements

  These are stateful components that act as tabs. A nav contains one or more `nav-link` or `dropdown`. It reports selections
  through an `onSelect` callback (which will send the ID of the item selected).

  The typical way to embed a nav is using the `nav` constructor in `InitialAppState` at some key, adding `Nav` to the query at
  the same key, and rendering it with `ui-nav`.

  The example below shows these steps:

  For demonstration purposes, we define this simple mutation to record the navigation change:

  ```clojure
  (m/defmutation nav-to [{:keys [page]}]
    (action [{:keys [state]}] (swap! state assoc :current-page page)))
  ```

  Then the following component can display nav and also show the current 'page'.
  "
  (dc/mkdn-pprint-source NavRoot))

(defcard nav-tabs (untangled-app NavRoot) {} {:inspect-data false})

(defui HomeScreen
  static uc/InitialAppState
  (initial-state [c p] {:screen-type :home})
  static om/IQuery
  (query [this] [:screen-type])
  Object
  (render [this]
    (dom/div nil "HOME SCREEN")))

(defui OtherScreen
  static uc/InitialAppState
  (initial-state [c p] {:screen-type :other})
  static om/IQuery
  (query [this] [:screen-type])
  Object
  (render [this]
    (dom/div nil "OTHER SCREEN")))

(defrouter MainRouter :main-router
  (ident [this props] [(:screen-type props) :singleton])
  :home HomeScreen
  :other OtherScreen)

(def ui-router (om/factory MainRouter))

(m/defmutation select-tab
  "Select the given tab"
  [{:keys [tab]}]
  (action [{:keys [state]}]
    (swap! state (fn [s]
                   (-> s
                     (routing/set-route :main-router [tab :singleton])
                     (b/set-active-nav-link* :main-nav tab))))))

(defui RouterRoot
  static uc/InitialAppState
  (initial-state [c p] {
                        :nav    (b/nav :main-nav :tabs :normal
                                  :home
                                  [(b/nav-link :home "Home" false) (b/nav-link :other "Other" false)])
                        :router (uc/get-initial-state MainRouter {})})
  static om/IQuery
  (query [this] [{:router (om/get-query MainRouter)} {:nav (om/get-query b/Nav)}])
  Object
  (render [this]
    (let [{:keys [router nav]} (om/props this)]
      (render-example "100%" "150px"
        (b/container-fluid {}
          (b/row {}
            (b/col {:xs 12} (b/ui-nav nav :onSelect #(om/transact! this `[(select-tab ~{:tab %})]))))
          (b/row {}
            (b/col {:xs 12} (ui-router router))))))))

(defcard-doc
  "## Combining with Untangled's UI routing

  The above component does nothing more than manage the UI of the tabs. You are still responsible for rendering the
  content of the application. You will likely wish to use the Untangled routing system so that your UI performance stays
  high, and HTML5 routing becomes simpler.

  ### Defining the UI Routing

  First, we'll need some screens to show. We'll just make some placeholders. They'll need to know (and query) their
  screen type:
  "
  (dc/mkdn-pprint-source HomeScreen)
  (dc/mkdn-pprint-source OtherScreen)
  "
  Then we'll need to define an ident that follows the `[type id]` semantic. Since there isn't really an ID, we'll just
  use the keyword `:singleton` for the ID.

  ```clojure
  (ident [this props] [(:screen-type props) :singleton])
  ```

  but this ident function goes with the UI router, which looks like this:

  ```clojure
  (defrouter MainRouter :main-router
    (ident [this props] [(:screen-type props) :singleton])
    :home HomeScreen
    :other OtherScreen)
  ```

  ### Composing the UI Elements

  Composing the UI requires that you query for the correct router and nav subelements, compose in their initial state,
  and render them both. Something like this will work:

  "
  (dc/mkdn-pprint-source RouterRoot)
  "
  ### Changing the Visible Screen

  This is now a component that can display whichever screen is currently active. The `untangled.client.routing` namespace
  includes a helper functions called `set-route` that can be composed into a mutation to change the current screen of
  a router:

  ```
  (m/defmutation select-tab
    \"Select the given tab\"
    [{:keys [tab]}]
    (action [{:keys [state]}]
      (swap! state routing/set-route :main-router [tab :singleton])))
  ```

  Of course, we could also be using the routing tree mechanisms, which would be similar (though you'd use the
  `update-routing-links` helper then).

  ### Ensuring the Correct Tab is Active

  If you're using something like HTML5 routing, then you'll not only need to make sure the current screen is showing,
  but you'll need to make sure the `Nav` knows which tab to show as active. The bootstrap namespace includes
  an Om mutation for setting the current nav link `set-active-nav-link`, and a helper function `set-active-nav-link*`
  that can be composed into mutations.

  If you're careful to make the IDs of the tabs and screens the same, then you'll be able to ensure lock-step
  updates with that same mutation:

  ```clojure
  (m/defmutation select-tab
    \"Select the given tab\"
    [{:keys [tab]}]
    (action [{:keys [state]}]
      (swap! state (fn [s]
                     (-> s
                       (routing/set-route :main-router [tab :singleton])
                       (b/set-active-nav-link* :main-nav tab))))))
  ```

  The overall technique works the same with routing trees, but in that case you'll need to know what nav links need
  to be updated for a given page and compose those instructions with the `routing/update-routing-links` helper.

  ## The Overall Result
  ")

(defcard nav-with-router (untangled-app RouterRoot) {} {:inspect-data false})

(defn person-ident
  "Returns an ident. Accepts either the full entity (props) or just an ID. Returns an ident for a person."
  [id-or-props]
  (if (map? id-or-props)
    [:person/by-id (:db/id id-or-props)]
    [:person/by-id id-or-props]))

(defui ^:once DemoPerson
  static om/Ident
  (ident [this props] (person-ident props))
  static om/IQuery
  (query [this] [:db/id :person/name])
  static uc/InitialAppState
  (initial-state [c {:keys [id name]}] {:db/id id :person/name name})
  ;; Just a simple component to display a person
  Object
  (render [this]
    (let [{:keys [db/id person/name]} (om/props this)]
      (b/container nil
        (b/row nil
          (b/col {:xs 4} "Name: ")
          (b/col {:xs 4} name))))))

(def ui-demoperson (om/factory DemoPerson))

(defui ^:once PersonEditor
  ; share the ident of a person, so we overlay the editor state on a person entity
  static om/Ident
  (ident [this props] (person-ident props))
  ; :ui/edited-name is a temporary place to put what the user is typing in the editor. saving will copy this to :person/name
  static om/IQuery
  (query [this] [:db/id :person/name :ui/edited-name])
  Object
  (render [this]
    (let [{:keys [db/id person/name ui/edited-name]} (om/props this)]
      (b/container nil
        (b/row nil
          (b/col {:xs 4} "Name: ")
          (b/col {:xs 4} (dom/input #js {:value    edited-name
                                         ; leverage helper transact that uses our ident to update data
                                         :onChange #(m/set-string! this :ui/edited-name :event %)})))))))

(def ui-person-editor (om/factory PersonEditor {:keyfn :db/id}))

(defn copy-edit-to-name*
  "Copy the current name of the person to the :ui/edited-name field for modal editing. This allows them to cancel
  the edit, or save it."
  [person]
  (assoc person :person/name (:ui/edited-name person)))

(defmutation save-person
  "Om mutation: Save a person. Takes the entire person entity as :person"
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
  "Om mutation: Start editing a person."
  [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state (fn [s] (-> s
                           (set-person-to-edit* id)
                           (initialize-edited-name* id))))))

(defui ^:once PersonModal
  ; We're storing both the real modal and the person-editor's state in this custom modal (which combines the two).
  ; The person-editor field will eventually need to point to the person to edit (by ident in the normalized db)
  ; When we get to routing, the :id of the modal will be what we use as the type of thing to route to...
  static uc/InitialAppState
  (initial-state [t p] {:person-editor nil :modal (uc/get-initial-state b/Modal {:id :edit-modal :backdrop true :keyboard false})})
  ; ident will come from UI router
  static om/IQuery
  (query [this] [{:person-editor (om/get-query PersonEditor)} {:modal (om/get-query b/Modal)}])
  Object
  (render [this]
    (let [{:keys [person-editor modal]} (om/props this)]
      ; The modal container
      (b/ui-modal modal
        (b/ui-modal-title nil
          (dom/b #js {:key "title"} "Person Editor"))
        (b/ui-modal-body nil
          ; NOTE: The person editor is embedded into the body. This gives us parallel data model of two concepts that
          ; have more of a tree relation in the UI.
          (ui-person-editor person-editor))
        (b/ui-modal-footer nil
          (b/button {:key "cancel" :onClick #(om/transact! this `[(b/hide-modal {:id :edit-modal})])} "Cancel")
          ; Save can be implemented with respect to the person data. Note, though, that the person-editor
          ; itself may be a stale copy (since the editor can refresh without re-rendering the modal)
          ; Thus, we use the ID from the person editor, which is stable in the context of an editing pop.
          (b/button {:key "save" :onClick #(om/transact! this `[(save-person {:id ~(:db/id person-editor)})
                                                                (b/hide-modal {:id :edit-modal})
                                                                :person/name]) :kind :primary} "Save"))))))

(defui ^:once WarningModal
  ; NOTE: When we get to routing, the :id of the modal will be what we use as the type of thing to route to...
  static uc/InitialAppState
  (initial-state [t p] {:message "Stuff broke" :modal (uc/get-initial-state b/Modal {:id :warning-modal :backdrop true})})
  ; ident will come from UI router
  static om/IQuery
  (query [this] [:message {:modal (om/get-query b/Modal)}]) ; so a mutation can change the message, in this case.
  Object
  (render [this]
    (let [{:keys [message modal]} (om/props this)]
      (b/ui-modal modal
        (b/ui-modal-title nil
          (dom/b #js {:key "warning"} " WARNING!"))
        (b/ui-modal-body nil
          (dom/p #js {:key "message" :className b/text-danger} message))
        (b/ui-modal-footer nil
          (b/button {:key "ok-button" :onClick #(om/transact! this `[(b/hide-modal {:id :warning-modal})])} "Bummer!"))))))

(def ui-warning-modal (om/factory WarningModal {:keyfn :id}))

(defrouter ModalRouter :modal-router
  ; REMEMBER: The ident determines the type of thing to render (the first element has to be the same
  ; as one of the keywords below). We're treating the id of the modal as the type, since they are singletons,
  ; and the two IDs are :warning-modal and :edit-modal. This means the ident function MUST place one of those
  ; keywords as the first element of the ident it returns.
  (ident [this props] [(-> props :modal :db/id) :singleton])
  :warning-modal WarningModal
  :edit-modal PersonModal)

(def ui-modal-router (om/factory ModalRouter))

(defn start-person-editor
  "Run an Om transaction that does all of the steps to edit a given person."
  [comp person-id]
  (om/transact! comp `[(routing/route-to {:handler :edit})  ; :edit is a route in the routing tree
                       (edit-person {:id ~person-id})
                       (b/show-modal {:id :edit-modal})
                       ; follow-on read ensures re-render at root
                       :modal-router]))

(defn show-warning
  "Run an Om transaction that does all of the steps to show a warning modal."
  [comp]
  (om/transact! comp `[(routing/route-to {:handler :warning}) ; :warning is a route in the routing tree
                       (b/show-modal {:id :warning-modal})
                       ; follow-on read ensures re-render at root
                       :modal-router]))

(defui ^:once ModalRoot
  static uc/InitialAppState
  (initial-state [c p] (merge
                         ; make a routing tree for the two modals and merge it in the app state
                         (routing/routing-tree
                           (routing/make-route :edit [(routing/router-instruction :modal-router [:edit-modal :singleton])])
                           (routing/make-route :warning [(routing/router-instruction :modal-router [:warning-modal :singleton])]))
                         ; general initial state
                         {:person       (uc/get-initial-state DemoPerson {:id 1 :name "Sam"})
                          :modal-router (uc/get-initial-state ModalRouter {})}))
  static om/IQuery
  (query [this] [{:person (om/get-query DemoPerson)} {:modal-router (om/get-query ModalRouter)}])
  Object
  (render [this]
    (let [{:keys [person modal-router]} (om/props this)]
      (render-example "100%" "500px"
        (dom/div nil
          (b/button {:onClick #(show-warning this)} "Show Warning")
          ; show the current value of the person
          (ui-demoperson person)
          (b/button {:onClick #(start-person-editor this (:db/id person))} "Edit Person")
          ; let the router render just the modal we need
          (ui-modal-router modal-router))))))

(defcard-doc
  "# Modals

  Modals are stateful Untangled components with app state and queries. They can, of course, be mixed with other
  app state.

  The basic usage is to define your modal in the root, with the various things it should render. If you need
  more than one kind of modal, then you can use a UI router and embed them all in it, then embed the router
  at the root.

  The following code demonstrates all of these techniques.

  ## The general UI Components

  See the comments in the code:

  "
  (dc/mkdn-pprint-source person-ident)
  (dc/mkdn-pprint-source DemoPerson)
  (dc/mkdn-pprint-source ui-demoperson)
  (dc/mkdn-pprint-source PersonEditor)
  (dc/mkdn-pprint-source ui-person-editor)
  (dc/mkdn-pprint-source PersonModal)
  (dc/mkdn-pprint-source WarningModal)
  "

  ## The UI Routing

  ```
  (defrouter ModalRouter :modal-router
    ; REMEMBER: The ident determines the type of thing to render (the first element has to be the same
    ; as one of the keywords below). We're treating the id of the modal as the type, since they are singletons,
    ; and the two IDs are :warning-modal and :edit-modal. This means the ident function MUST place one of those
    ; keywords as the first element of the ident it returns.
    (ident [this props] [(-> props :modal :db/id) :singleton])
    :warning-modal WarningModal
    :edit-modal PersonModal)
  ```
  "
  (dc/mkdn-pprint-source ui-modal-router)
  (dc/mkdn-pprint-source ModalRoot)
  (dc/mkdn-pprint-source start-person-editor)
  (dc/mkdn-pprint-source show-warning)
  "## The Mutations

  We define a couple of helper functions that can work on app state to accomplish some tasks we need:

  "
  (dc/mkdn-pprint-source copy-edit-to-name*)
  (dc/mkdn-pprint-source set-person-to-edit*)
  (dc/mkdn-pprint-source initialize-edited-name*)
  "

  and this makes the necessary mutations very simple to write (and also a lot more readable):

  ```
  (defmutation save-person
    \"Om mutation: Save a person. Takes the entire person entity as :person\"
    [{:keys [id]}]
    (action [{:keys [state]}]
      (swap! state update-in (person-ident id) copy-edit-to-name*)))


  (defmutation edit-person
    \"Om mutation: Start editing a person.\"
    [{:keys [id]}]
    (action [{:keys [state]}]
      (swap! state (fn [s] (-> s
                             (set-person-to-edit* id)
                             (initialize-edited-name* id))))))

  ```
  ")

(defcard modal
  (untangled-app ModalRoot)
  {}
  {:inspect-data false})

(defcard modal-variation-small
  (render-example "100%" "300px"
    (dom/div nil
      (ui-warning-modal {:message "This is a small modal."
                         :modal   {:id :small :modal/active true :modal/visible true :modal/size :sm :backdrop false}}))))

(defcard modal-variation-default-size
  (render-example "100%" "300px"
    (dom/div nil
      (ui-warning-modal {:message "This is a regular modal."
                         :modal   {:id :dflt :modal/active true :modal/visible true :backdrop false}}))))

(defcard modal-variation-large
  "NOTE: The iframe for this example is forced larger than the devcard because large modals adapt down in size based on
  available space."
  (render-example "1024px" "300px"
    (dom/div nil
      (ui-warning-modal {:message "This is a large modal."
                         :modal   {:id :large :modal/active true :modal/visible true :modal/size :lg :backdrop false}}))))

(defui GridModal
  Object
  (render [this]
    (b/ui-modal (om/props this)
      (b/ui-modal-title {:key "title"} "A Modal Using a Grid")
      (b/ui-modal-body {:key "my-body"}
        "body"
        (b/row {:key "row"}
          (b/col {:xs 3}
            "Column 1 xs 3")
          (b/col {:xs 3}
            "Column 2 xs 3")
          (b/col {:xs 3}
            "Column 3 xs 3")
          (b/col {:xs 3}
            "Column 4 xs 3"))))))

(def ui-grid-modal (om/factory GridModal {:keyfn :id}))

(defcard-doc
  "Modals are allowed to use the grid within the body without a container:"
  (dc/mkdn-pprint-source GridModal))

(defcard modal-with-grid
  (render-example "100%" "200px"
    (ui-grid-modal {:id :my-modal :modal/visible true :modal/active true})))

(defui ^:once CollapseRoot
  ; Use the initial state of b/Collapse to make the proper state for one
  static uc/InitialAppState
  (initial-state [c p] {:collapse-1 (uc/get-initial-state b/Collapse {:id 1 :start-open false})})
  ; Join it into your query
  static om/IQuery
  (query [this] [{:collapse-1 (om/get-query b/Collapse)}])
  Object
  (render [this]
    (let [{:keys [collapse-1]} (om/props this)]             ; pull it out of props
      (render-example "100%" "200px"
        (dom/div nil
          (b/button {:onClick (fn [] (om/transact! this `[(b/toggle-collapse {:id 1})]))} "Toggle")
          ; Wrap the elements to be hidden as children
          ; NOTE: if the children need props they could be queried for above and passed in here.
          (b/ui-collapse collapse-1
            (dom/div #js {:className "well"} "This is some content that can be collapsed.")))))))

(defcard-doc
  "# Collapse

  The collapse item is a stateful component that takes children. The children will be hidden/shown based on the
  state of the collapse wrapper.

  The built-in mutation `(b/toggle-collapse {:id ID-OF-COLLAPSE})` can be used to toggle a specific item, and
  the mutation `(b/set-collapse {:id ID :open BOOLEAN})` can be used to set the specific state. NOTE: Both of
  these mutations automatically do nothing if an animation is in progress.

  An example with these elements combined looks like this:

  "
  (dc/mkdn-pprint-source CollapseRoot))

(defcard collapse-card
  "The live version of the collapse in action:"
  (untangled-app CollapseRoot))

(defn accordian-section [this all-ids collapse]
  (letfn [(toggle [] (om/transact! this `[(b/toggle-collapse-group-item {:item-id      ~(:db/id collapse)
                                                                         :all-item-ids ~all-ids})]))]
    (b/panel nil
      (b/panel-heading nil
        (b/panel-title nil
          (dom/a #js {:onClick toggle} "Section Heading")))
      (b/ui-collapse collapse
        (b/panel-body nil
          "This is some content that can be collapsed.")))))

(defui ^:once CollapseGroupRoot
  ; Create a to-many list of collapse items in app state (or you could do them one by one)
  static uc/InitialAppState
  (initial-state [c p] {:collapses [(uc/get-initial-state b/Collapse {:id 1 :start-open false})
                                    (uc/get-initial-state b/Collapse {:id 2 :start-open false})
                                    (uc/get-initial-state b/Collapse {:id 3 :start-open false})
                                    (uc/get-initial-state b/Collapse {:id 4 :start-open false})]})
  ; join it into the query
  static om/IQuery
  (query [this] [{:collapses (om/get-query b/Collapse)}])
  Object
  (render [this]
    (let [{:keys [collapses]} (om/props this)               ; pull from db
          all-ids [1 2 3 4]]                                ; convenience for all ids
      (render-example "100%" "300px"
        ; map over our helper function
        (b/panel-group nil
          (map (fn [c] (accordian-section this all-ids c)) collapses))))))

(defcard-doc
  "# Accordian (group of collapse)

  An accordian is supported by simply another mutation:
  `(b/toggle-collapse-group-item {:item-id ID :all-item-ids IDs)`. This mutation
  toggles the specific item (by ID), and it understands the grouping because you also pass it
  the IDs of all of the other items in the group. There's no real need for them to be any
  more tightly coupled.

  For example, say we choose to use a `panel-group` to lay out each item. Each item can be a
   panel, so we'll define this extra helper function (not part of the library):

  "
  (dc/mkdn-pprint-source accordian-section)
  "
  Then create the instances in app state an map over them with our helper:
  "
  (dc/mkdn-pprint-source CollapseGroupRoot)
  "
  Note that you can use the mutations defined for single Collapse elements (of course), but that will cause
  strangeness in your accordian (e.g. two sections open at once). The `toggle-collapse-group-item` *does*
  correct this by closing all open sections except the one being opened.
  ")

(defcard collapse-group-card
  "Live Accordian"
  (untangled-app CollapseGroupRoot))
