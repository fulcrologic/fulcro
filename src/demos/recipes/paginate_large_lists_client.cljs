(ns recipes.paginate-large-lists-client
  (:require
    [fulcro.client.core :as fc]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.mutations :as m]
    [om.dom :as dom]
    [om.next :as om :refer [defui]]))


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
  static om/IQuery
  (query [this] [:item/id :ui/fetch-state])
  static om/Ident
  (ident [this props] [:items/by-id (:item/id props)])
  Object
  (render [this]
    (dom/li nil (str "Item " (-> this om/props :item/id)))))

(def ui-list-item (om/factory ListItem {:keyfn :item/id}))

(defui ^:once ListPage
  static fc/InitialAppState
  (initial-state [c p] {:page/number 1 :page/items []})
  static om/IQuery
  (query [this] [:page/number {:page/items (om/get-query ListItem)}])
  static om/Ident
  (ident [this props] [:page/by-number (:page/number props)])
  Object
  (render [this]
    (let [{:keys [page/number page/items]} (om/props this)]
      (dom/div nil
        (dom/p nil "Page number " number)
        (df/lazily-loaded #(dom/ul nil (mapv ui-list-item %)) items)))))

(def ui-list-page (om/factory ListPage {:keyfn :page/number}))

(defui ^:once LargeList
  static fc/InitialAppState
  (initial-state [c params] {:list/current-page (fc/get-initial-state ListPage {})})
  static om/IQuery
  (query [this] [{:list/current-page (om/get-query ListPage)}])
  static om/Ident
  (ident [this props] [:list/by-id 1])
  Object
  (render [this]
    (let [{:keys [list/current-page]} (om/props this)
          {:keys [page/number]} current-page]
      (dom/div nil
        (dom/button #js {:disabled (= 1 number) :onClick #(om/transact! this `[(goto-page {:page-number ~(dec number)})])} "Prior Page")
        (dom/button #js {:onClick #(om/transact! this `[(goto-page {:page-number ~(inc number)})])} "Next Page")
        (ui-list-page current-page)))))

(def ui-list (om/factory LargeList))

(defui ^:once Root
  static fc/InitialAppState
  (initial-state [c params] {:pagination/list (fc/get-initial-state LargeList {})})
  static om/IQuery
  (query [this] [:ui/react-key {:pagination/list (om/get-query LargeList)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key pagination/list] :or {ui/react-key "ROOT"}} (om/props this)]
      (dom/div #js {:key react-key} (ui-list list)))))


