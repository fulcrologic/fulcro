(ns cards.paginating-large-lists-from-server
  (:require
    #?@(:cljs [[devcards.core :as dc :include-macros true]
               [fulcro.client.cards :refer [defcard-fulcro]]])
    [fulcro.client.mutations :as m]
    [fulcro.client.primitives :as prim :refer [defui defsc]]
    [fulcro.client.dom :as dom]
    [fulcro.server :as server]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
    [fulcro.client.core :as fc]
    [fulcro.client.primitives :as prim]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(server/defquery-root :paginate/items
  "A simple implementation that can generate any number of items whose ids just match their index"
  (value [env {:keys [start end]}]
    #?(:clj (Thread/sleep 100))
    (when (> 1000 (- end start))
      (vec (for [id (range start end)]
             {:item/id id})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLIENT:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn page-exists? [state-map page-number]
  (let [page-items (get-in state-map [:page/by-number page-number :page/items])]
    (boolean (seq page-items))))

(defn init-page
  "An idempotent init function that just ensures enough of a page exists to make the UI work.
   Doesn't affect the items."
  [state-map page-number]
  (assoc-in state-map [:page/by-number page-number :page/number] page-number))

(defn set-current-page
  "Point the current list's current page to the correct page entity in the db (via ident)."
  [state-map page-number]
  (assoc-in state-map [:list/by-id 1 :list/current-page] [:page/by-number page-number]))

(defn clear-item
  "Removes the given item from the item table."
  [state-map item-id] (update state-map :items/by-id dissoc item-id))

(defn clear-page
  "Clear the given page (and associated items) from the app database."
  [state-map page-number]
  (let [page        (get-in state-map [:page/by-number page-number])
        item-idents (:page/items page)
        item-ids    (map second item-idents)]
    (as-> state-map s
      (update s :page/by-number dissoc page-number)
      (reduce (fn [acc id] (update acc :items/by-id dissoc id)) s item-ids))))

(defn gc-distant-pages
  "Clears loaded items from pages 5 or more steps away from the given page number."
  [state-map page-number]
  (reduce (fn [s n]
            (if (< 4 (Math/abs (- page-number n)))
              (clear-page s n)
              s)) state-map (keys (:page/by-number state-map))))

(declare ListItem)

(defn load-if-missing [{:keys [reconciler state] :as env} page-number]
  (when-not (page-exists? @state page-number)
    (let [start (inc (* 10 (dec page-number)))
          end   (+ start 9)]
      (df/load reconciler :paginate/items ListItem {:params {:start start :end end}
                                                    :target [:page/by-number page-number :page/items]}))))

(m/defmutation goto-page [{:keys [page-number]}]
  (action [{:keys [state] :as env}]
    (load-if-missing env page-number)
    (swap! state (fn [s]
                   (-> s
                     (init-page page-number)
                     (set-current-page page-number)
                     (gc-distant-pages page-number)))))
  (remote [{:keys [state] :as env}]
    (when (not (page-exists? @state page-number))
      (df/remote-load env))))

(defui ^:once ListItem
  static prim/IQuery
  (query [this] [:item/id :ui/fetch-state])
  static prim/Ident
  (ident [this props] [:items/by-id (:item/id props)])
  Object
  (render [this]
    (dom/li nil (str "Item " (-> this prim/props :item/id)))))

(def ui-list-item (prim/factory ListItem {:keyfn :item/id}))

(defui ^:once ListPage
  static prim/InitialAppState
  (initial-state [c p] {:page/number 1 :page/items []})
  static prim/IQuery
  (query [this] [:page/number {:page/items (prim/get-query ListItem)}])
  static prim/Ident
  (ident [this props] [:page/by-number (:page/number props)])
  Object
  (render [this]
    (let [{:keys [page/number page/items]} (prim/props this)]
      (dom/div nil
        (dom/p nil "Page number " number)
        (df/lazily-loaded #(dom/ul nil (mapv ui-list-item %)) items)))))

(def ui-list-page (prim/factory ListPage {:keyfn :page/number}))

(defui ^:once LargeList
  static prim/InitialAppState
  (initial-state [c params] {:list/current-page (prim/get-initial-state ListPage {})})
  static prim/IQuery
  (query [this] [{:list/current-page (prim/get-query ListPage)}])
  static prim/Ident
  (ident [this props] [:list/by-id 1])
  Object
  (render [this]
    (let [{:keys [list/current-page]} (prim/props this)
          {:keys [page/number]} current-page]
      (dom/div nil
        (dom/button #js {:disabled (= 1 number) :onClick #(prim/transact! this `[(goto-page {:page-number ~(dec number)})])} "Prior Page")
        (dom/button #js {:onClick #(prim/transact! this `[(goto-page {:page-number ~(inc number)})])} "Next Page")
        (ui-list-page current-page)))))

(def ui-list (prim/factory LargeList))

(defui ^:once Root
  static prim/InitialAppState
  (initial-state [c params] {:pagination/list (prim/get-initial-state LargeList {})})
  static prim/IQuery
  (query [this] [:ui/react-key {:pagination/list (prim/get-query LargeList)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key pagination/list] :or {ui/react-key "ROOT"}} (prim/props this)]
      (dom/div #js {:key react-key} (ui-list list)))))


#?(:cljs
   (defcard-fulcro paginate-list-card
     "
     # Paginating Large Lists

     Note: This is a full-stack example. Make sure you're running the server and are serving this page from it.

     This demo is showing a (dynamically generated) list of items. The server can generate any number of them, so you
     can page ahead as many times as you like. Each page is dynamically loaded if and only if the browser does not already
     have it. The server has an intentional delay so you can be sure to see when the loads happen. The initial startup
     loads the first page automatically (reload this page if you missed that).

     The demo also ensure you cannot run out of browser memory by removing items and pages that are more than 4 steps away
     from your current position. You can demostrate this by moving ahead by more than 4 pages, then page back 5. You should
     see a reload of that early page when you go back to it.
     "
     Root
     {}
     {:inspect-data false
      :fulcro       {:started-callback (fn [{:keys [reconciler]}]
                                         (prim/transact! reconciler `[(goto-page {:page-number 1})]))}}))

#?(:cljs
   (dc/defcard-doc
     "# Explanation

     The UI of this example is a great example of how a complex application behavior remains very very simple at the UI
     layer with Fulcro.

     We represent the list items as you might expect:
     "
     (dc/mkdn-pprint-source ListItem)
     "
     We then generate a component to represent a page of them. This allows us to associate the items on a page with
     a particular component, which makes tracking the page number and items on that page much simpler:
     "
     (dc/mkdn-pprint-source ListPage)
     "
     Next we build a component to control which page we're on called `LargeList`. This component does nothing more than
     show the current page, and transact mutations that ask for the specific page. Not that we could easily add a control
     to jump to any page, since the mutation itself is `goto-page`.
     "
     (dc/mkdn-pprint-source LargeList)
     (dc/mkdn-pprint-source Root)
     "## The Mutation

     So you can infer that all of the complexity of this application is hidden behind a single mutation: `goto-page`. This
     mutation is a complete abstraction to the UI, and the UI designer would need to very little about it.

     We've decided that this mutation will:

     - Ensure the given page exists in app state (with its page number)
     - Check to see if the page has items
       - If not: it will trigger a server-side query for those items
     - Update the `LargeList`'s current page to point to the correct page
     - Garbage collect pages/items in the app database that are 5 or more pages away from the current position.

     The mutation itself looks like this:

     ```
     (m/defmutation goto-page [{:keys [page-number]}]
       (action [{:keys [state] :as env}]
         (load-if-missing env page-number)
         (swap! state (fn [s]
                        (-> s
                          (init-page page-number)
                          (set-current-page page-number)
                          (gc-distant-pages page-number)))))
       (remote [{:keys [state] :as env}]
         (when (not (page-exists? @state page-number))
           (df/remote-load env)))) ; trigger load queue processing
     ```

     Let's break this down.

     ### The Action

     The `load-if-missing` function is composed of the following bits:
     "
     (dc/mkdn-pprint-source page-exists?)
     (dc/mkdn-pprint-source load-if-missing)
     "
     and you can see that it just detects if the page is missing its items. If the items are missing, it uses the
     `load-action` function (which adds a load entry to the load queue from within a mutation). The parameters
     to `load-action` are nearly identical to `load`, except for the first argument (which for `load-action` is
     the env of the mutation). Note that when adding an item to the load queue from within a mutation, the mutation
     should also trigger the load queue processing via the remote side of the mutation via `remote-load` (as shown above).

     The call to `swap!` lets us compose together the remaining actions: `init-page`, `set-current-page`,
     and `gc-distant-pages`. Those should really not be surprising at all, since they are just local manipulations of
     the database. The source for them is below:
     "
     (dc/mkdn-pprint-source init-page)
     (dc/mkdn-pprint-source set-current-page)
     (dc/mkdn-pprint-source clear-item)
     (dc/mkdn-pprint-source clear-page)
     (dc/mkdn-pprint-source gc-distant-pages)
     "## The Server-Side Code

     The server in this example is trivial. It is just a query that generates items on the fly with a slight delay:

     ```
     (defmethod api/server-read :paginate/items [env k {:keys [start end]}]
       (Thread/sleep 100)
       (when (> 1000 (- end start)) ; ensure the server doesn't die if the client does something like use NaN for end
         {:value (vec (for [id (range start end)]
                        {:item/id id}))}))
     ```
     "))


