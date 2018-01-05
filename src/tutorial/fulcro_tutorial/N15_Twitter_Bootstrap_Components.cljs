(ns fulcro-tutorial.N15-Twitter-Bootstrap-Components
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [fulcro.client.routing :as routing :refer [defrouter]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc deftest]]
            [cljs.reader :as r]
            [fulcro.client.impl.parser :as p]
            [fulcro-tutorial.N10-Twitter-Bootstrap-CSS :refer [render-example sample]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.ui.elements :as ele]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.ui.bootstrap3 :as b]
            [fulcro.client :as fc]
            [devcards.core :as dc]))

(defsc DropdownRoot [this {:keys [dropdown dropdown-2]}]
  {:initial-state (fn [params] {:dropdown   (b/dropdown :file "File" [(b/dropdown-item :open "Open")
                                                                      (b/dropdown-item :close "Close")
                                                                      (b/dropdown-divider :divider-1)
                                                                      (b/dropdown-item :quit "Quit")])
                                :dropdown-2 (b/dropdown :select "Select One" [(b/dropdown-item :a "A")
                                                                              (b/dropdown-item :b "B")])})
   :query         [{:dropdown (prim/get-query b/Dropdown)} {:dropdown-2 (prim/get-query b/Dropdown)}]}
  (render-example "100%" "150px"
    (let [select (fn [id] (js/alert (str "Selected: " id)))]
      (dom/div #js {:height "100%" :onClick #(prim/transact! this `[(b/close-all-dropdowns {})])}
        (b/ui-dropdown dropdown :onSelect select :kind :success)
        (b/ui-dropdown dropdown-2 :onSelect select :kind :success :stateful? true)))))

(defcard-doc
  "
  # Active Dropdown Component

  Active dropdowns are components with state.

  - `dropdown` - a function that creates a dropdown's state
  - `dropdown-item` - a function that creates an items with a label
  - `ui-dropdown` - renders the dropdown. It requires the dropdown's properties, and allows optional named arguments:
     - `:onSelect` the callback for selection. It is given the selected element's id
     - `:kind` Identical to the `button` `:kind` attribute.

  All labels are run through `tr-unsafe`, so if a translation for the current locale exists it will be used.

  The following mutations are are available:

  Close (or open) a specific dropdown by ID:
  ```
  (prim/transact! component `[(b/set-dropdown-open {:id :dropdown :open? false})]`)
  ```

  Close dropdowns (globally). Useful for capturing clicks a the root to close dropdowns when the user clicks outside of
  an open dropdown.

  ```
  (prim/transact! component `[(b/close-all-dropdowns {})])
  ```

  You can set highlighting on an item in the menu (to mark it as active) with:

  ```
  (prim/transact! component `[(b/set-dropdown-item-active {:id :item-id :active? true})])
  ```

  An example usage (embedded in an example renderer that includes the bootstrap css):

  "
  (dc/mkdn-pprint-source DropdownRoot)
  "

  generates the dropdown in the card below.
  ")

(defcard-fulcro dropdown DropdownRoot {} {:inspect-data false})

(m/defmutation nav-to [{:keys [page]}]
  (action [{:keys [state]}] (swap! state assoc :current-page page)))

(defsc NavRoot [this {:keys [nav current-page]}]
  {
   :initial-state (fn [props] {:current-page :home
                               ; Embed the nav in app state as a child of this component
                               :nav          (b/nav :main-nav :tabs :normal
                                               :home
                                               [(b/nav-link :home "Home" false)
                                                (b/nav-link :other "Other" false)
                                                (b/dropdown :reports "Reports"
                                                  [(b/dropdown-item :report-1 "Report 1")
                                                   (b/dropdown-item :report-2 "Report 2")])])})
   ; make sure to add the join on the same keyword (:nav)
   :query         [:current-page {:nav (prim/get-query b/Nav)}]}
  (render-example "100%" "150px"
    (b/container-fluid {}
      (b/row {}
        (b/col {:xs 12}
          ; render it, use onSelect to be notified when nav changes. Note: `nav-to` is just part of this demo.
          (b/ui-nav nav :onSelect (fn [id] (prim/transact! this `[(nav-to ~{:page id})])))))
      (b/row {}
        (b/col {:xs 12}
          (dom/p #js {} (str "Current page: " current-page)))))))

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

(defcard-fulcro nav-tabs NavRoot {} {:inspect-data false})

(defsc HomeScreen [this props]
  {:initial-state {:screen-type :home}
   :query         [:screen-type]}
  (dom/div nil "HOME SCREEN"))

(defsc OtherScreen [this props]
  {:initial-state {:screen-type :other}
   :query         [:screen-type]}
  (dom/div nil "OTHER SCREEN"))

(defrouter MainRouter :main-router
  (ident [this props] [(:screen-type props) :singleton])
  :home HomeScreen
  :other OtherScreen)

(def ui-router (prim/factory MainRouter))

(m/defmutation select-tab
  "Select the given tab"
  [{:keys [tab]}]
  (action [{:keys [state]}]
    (swap! state (fn [s]
                   (-> s
                     (routing/set-route :main-router [tab :singleton])
                     (b/set-active-nav-link* :main-nav tab))))))

(defsc RouterRoot [this {:keys [router nav]}]
  {:initial-state (fn [p] {
                           :nav    (b/nav :main-nav :tabs :normal
                                     :home
                                     [(b/nav-link :home "Home" false) (b/nav-link :other "Other" false)])
                           :router (prim/get-initial-state MainRouter {})})
   :query         [{:router (prim/get-query MainRouter)} {:nav (prim/get-query b/Nav)}]}
  (render-example "100%" "150px"
    (b/container-fluid {}
      (b/row {}
        (b/col {:xs 12} (b/ui-nav nav :onSelect #(prim/transact! this `[(select-tab ~{:tab %})]))))
      (b/row {}
        (b/col {:xs 12} (ui-router router))))))

(defcard-doc
  "## Combining with Fulcro's UI routing

  The above component does nothing more than manage the UI of the tabs. You are still responsible for rendering the
  content of the application. You will likely wish to use the Fulcro routing system so that your UI performance stays
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

  This is now a component that can display whichever screen is currently active. The `fulcro.client.routing` namespace
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
  an mutation for setting the current nav link `set-active-nav-link`, and a helper function `set-active-nav-link*`
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

(defcard-fulcro nav-with-router RouterRoot)

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

(defcard-doc
  "# Modals

  Modals are stateful Fulcro components with app state and queries. They can, of course, be mixed with other
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
    \"mutation: Save a person. Takes the entire person entity as :person\"
    [{:keys [id]}]
    (action [{:keys [state]}]
      (swap! state update-in (person-ident id) copy-edit-to-name*)))


  (defmutation edit-person
    \"mutation: Start editing a person.\"
    [{:keys [id]}]
    (action [{:keys [state]}]
      (swap! state (fn [s] (-> s
                             (set-person-to-edit* id)
                             (initialize-edited-name* id))))))

  ```
  ")

(defcard-fulcro modal
  ModalRoot
  {}
  {:inspect-data true
   :fulcro       {:started-callback (fn [app] (js/console.log :STARTED!))}})

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

(defsc GridModal [this props]
  (b/ui-modal (prim/props this)
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
          "Column 4 xs 3")))))

(def ui-grid-modal (prim/factory GridModal {:keyfn :id}))

(defcard-doc
  "Modals are allowed to use the grid within the body without a container:"
  (dc/mkdn-pprint-source GridModal))

(defcard modal-with-grid
  (render-example "100%" "200px"
    (ui-grid-modal {:id :my-modal :modal/visible true :modal/active true})))

(defsc CollapseRoot [this {:keys [collapse-1]}]
  {; Use the initial state of b/Collapse to make the proper state for one
   :initial-state (fn [p] {:collapse-1 (prim/get-initial-state b/Collapse {:id 1 :start-open false})})
   ; Join it into your query
   :query         [{:collapse-1 (prim/get-query b/Collapse)}]}
  (render-example "100%" "200px"
    (dom/div nil
      (b/button {:onClick (fn [] (prim/transact! this `[(b/toggle-collapse {:id 1})]))} "Toggle")
      ; Wrap the elements to be hidden as children
      ; NOTE: if the children need props they could be queried for above and passed in here.
      (b/ui-collapse collapse-1
        (dom/div #js {:className "well"} "This is some content that can be collapsed.")))))

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

(defcard-fulcro collapse-card
  "The live version of the collapse in action:"
  CollapseRoot)

(defn accordian-section [this all-ids collapse]
  (letfn [(toggle [] (prim/transact! this `[(b/toggle-collapse-group-item {:item-id      ~(:db/id collapse)
                                                                           :all-item-ids ~all-ids})]))]
    (b/panel {:key (str "section-" (:db/id collapse))}
      (b/panel-heading {:key (str "heading-" (:db/id collapse))}
        (b/panel-title nil
          (dom/a #js {:onClick toggle} "Section Heading")))
      (b/ui-collapse collapse
        (b/panel-body nil
          "This is some content that can be collapsed.")))))

(defsc CollapseGroupRoot [this {:keys [ui/react-key collapses]}]
  {; Create a to-many list of collapse items in app state (or you could do them one by one)
   :initial-state (fn [p] {:collapses [(prim/get-initial-state b/Collapse {:id 1 :start-open false})
                                       (prim/get-initial-state b/Collapse {:id 2 :start-open false})
                                       (prim/get-initial-state b/Collapse {:id 3 :start-open false})
                                       (prim/get-initial-state b/Collapse {:id 4 :start-open false})]})
   ; join it into the query
   :query         [:ui/react-key {:collapses (prim/get-query b/Collapse)}]}
  (let [all-ids [1 2 3 4]]                                  ; convenience for all ids
    (render-example "100%" "300px"
      ; map over our helper function
      (b/panel-group {:key react-key}
        (mapv (fn [c] (accordian-section this all-ids c)) collapses)))))

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

(defcard-fulcro collapse-group-card
  "Live Accordian"
  CollapseGroupRoot)

(defsc CarouselExample [this {:keys [carousel]}]
  {
   :initial-state (fn [p] {:carousel (prim/get-initial-state b/Carousel {:id :sample :interval 2000})})
   :query         [{:carousel (prim/get-query b/Carousel)}]}
  (render-example "100%" "400px"
    (b/ui-carousel carousel
      (b/ui-carousel-item {:src "data:image/svg+xml;base64,PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0iVVRGLTgiIHN0YW5kYWxvbmU9InllcyI/PjxzdmcgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIiB3aWR0aD0iOTAwIiBoZWlnaHQ9IjUwMCIgdmlld0JveD0iMCAwIDkwMCA1MDAiIHByZXNlcnZlQXNwZWN0UmF0aW89Im5vbmUiPjwhLS0KU291cmNlIFVSTDogaG9sZGVyLmpzLzkwMHg1MDAvYXV0by8jNzc3OiM1NTUvdGV4dDpGaXJzdCBzbGlkZQpDcmVhdGVkIHdpdGggSG9sZGVyLmpzIDIuNi4wLgpMZWFybiBtb3JlIGF0IGh0dHA6Ly9ob2xkZXJqcy5jb20KKGMpIDIwMTItMjAxNSBJdmFuIE1hbG9waW5za3kgLSBodHRwOi8vaW1za3kuY28KLS0+PGRlZnM+PHN0eWxlIHR5cGU9InRleHQvY3NzIj48IVtDREFUQVsjaG9sZGVyXzE1Y2QxZTI2YzkxIHRleHQgeyBmaWxsOiM1NTU7Zm9udC13ZWlnaHQ6Ym9sZDtmb250LWZhbWlseTpBcmlhbCwgSGVsdmV0aWNhLCBPcGVuIFNhbnMsIHNhbnMtc2VyaWYsIG1vbm9zcGFjZTtmb250LXNpemU6NDVwdCB9IF1dPjwvc3R5bGU+PC9kZWZzPjxnIGlkPSJob2xkZXJfMTVjZDFlMjZjOTEiPjxyZWN0IHdpZHRoPSI5MDAiIGhlaWdodD0iNTAwIiBmaWxsPSIjNzc3Ii8+PGc+PHRleHQgeD0iMzA4LjI5Njg3NSIgeT0iMjcwLjEiPkZpcnN0IHNsaWRlPC90ZXh0PjwvZz48L2c+PC9zdmc+" :alt "1"})
      (b/ui-carousel-item {:src "data:image/svg+xml;base64,PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0iVVRGLTgiIHN0YW5kYWxvbmU9InllcyI/PjxzdmcgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIiB3aWR0aD0iOTAwIiBoZWlnaHQ9IjUwMCIgdmlld0JveD0iMCAwIDkwMCA1MDAiIHByZXNlcnZlQXNwZWN0UmF0aW89Im5vbmUiPjwhLS0KU291cmNlIFVSTDogaG9sZGVyLmpzLzkwMHg1MDAvYXV0by8jNjY2OiM0NDQvdGV4dDpTZWNvbmQgc2xpZGUKQ3JlYXRlZCB3aXRoIEhvbGRlci5qcyAyLjYuMC4KTGVhcm4gbW9yZSBhdCBodHRwOi8vaG9sZGVyanMuY29tCihjKSAyMDEyLTIwMTUgSXZhbiBNYWxvcGluc2t5IC0gaHR0cDovL2ltc2t5LmNvCi0tPjxkZWZzPjxzdHlsZSB0eXBlPSJ0ZXh0L2NzcyI+PCFbQ0RBVEFbI2hvbGRlcl8xNWNkMWUyODg2NSB0ZXh0IHsgZmlsbDojNDQ0O2ZvbnQtd2VpZ2h0OmJvbGQ7Zm9udC1mYW1pbHk6QXJpYWwsIEhlbHZldGljYSwgT3BlbiBTYW5zLCBzYW5zLXNlcmlmLCBtb25vc3BhY2U7Zm9udC1zaXplOjQ1cHQgfSBdXT48L3N0eWxlPjwvZGVmcz48ZyBpZD0iaG9sZGVyXzE1Y2QxZTI4ODY1Ij48cmVjdCB3aWR0aD0iOTAwIiBoZWlnaHQ9IjUwMCIgZmlsbD0iIzY2NiIvPjxnPjx0ZXh0IHg9IjI2NC45NTMxMjUiIHk9IjI3MC4xIj5TZWNvbmQgc2xpZGU8L3RleHQ+PC9nPjwvZz48L3N2Zz4=" :alt "2"})
      (b/ui-carousel-item {:src "data:image/svg+xml;base64,PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0iVVRGLTgiIHN0YW5kYWxvbmU9InllcyI/PjxzdmcgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIiB3aWR0aD0iOTAwIiBoZWlnaHQ9IjUwMCIgdmlld0JveD0iMCAwIDkwMCA1MDAiIHByZXNlcnZlQXNwZWN0UmF0aW89Im5vbmUiPjwhLS0KU291cmNlIFVSTDogaG9sZGVyLmpzLzkwMHg1MDAvYXV0by8jNTU1OiMzMzMvdGV4dDpUaGlyZCBzbGlkZQpDcmVhdGVkIHdpdGggSG9sZGVyLmpzIDIuNi4wLgpMZWFybiBtb3JlIGF0IGh0dHA6Ly9ob2xkZXJqcy5jb20KKGMpIDIwMTItMjAxNSBJdmFuIE1hbG9waW5za3kgLSBodHRwOi8vaW1za3kuY28KLS0+PGRlZnM+PHN0eWxlIHR5cGU9InRleHQvY3NzIj48IVtDREFUQVsjaG9sZGVyXzE1Y2QxZTI3MmM4IHRleHQgeyBmaWxsOiMzMzM7Zm9udC13ZWlnaHQ6Ym9sZDtmb250LWZhbWlseTpBcmlhbCwgSGVsdmV0aWNhLCBPcGVuIFNhbnMsIHNhbnMtc2VyaWYsIG1vbm9zcGFjZTtmb250LXNpemU6NDVwdCB9IF1dPjwvc3R5bGU+PC9kZWZzPjxnIGlkPSJob2xkZXJfMTVjZDFlMjcyYzgiPjxyZWN0IHdpZHRoPSI5MDAiIGhlaWdodD0iNTAwIiBmaWxsPSIjNTU1Ii8+PGc+PHRleHQgeD0iMjk4LjMyMDMxMjUiIHk9IjI3MC4xIj5UaGlyZCBzbGlkZTwvdGV4dD48L2c+PC9nPjwvc3ZnPg==" :alt "3"}))))

#_(defcard-doc
    "# Carousel

    The carousel has a number of configurable options

    "
    (dc/mkdn-pprint-source CarouselExample))

#_(defcard-fulcro
    "# Carousel Live Demo"
    CarouselExample)
