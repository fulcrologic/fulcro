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
            [untangled.client.mutations :as m]
            [untangled.ui.bootstrap3 :as b]
            [untangled.client.core :as uc]))

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
