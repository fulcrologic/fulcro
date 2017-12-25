(ns book.demos.paginating-large-lists-from-server
  (:require
    [fulcro.client.mutations :as m]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.client.dom :as dom]
    [fulcro.server :as server]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
    [fulcro.client :as fc]
    [fulcro.client.primitives :as prim]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(server/defquery-root :paginate/items
  "A simple implementation that can generate any number of items whose ids just match their index"
  (value [env {:keys [start end]}]
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

(defsc ListItem [this {:keys [item/id]}]
  {:query [:item/id :ui/fetch-state]
   :ident [:items/by-id :item/id]}
  (dom/li nil (str "Item " id)))

(def ui-list-item (prim/factory ListItem {:keyfn :item/id}))

(defsc ListPage [this {:keys [page/number page/items]}]
  {:initial-state {:page/number 1 :page/items []}
   :query         [:page/number {:page/items (prim/get-query ListItem)}]
   :ident         [:page/by-number :page/number]}
  (dom/div nil
    (dom/p nil "Page number " number)
    (df/lazily-loaded #(dom/ul nil (mapv ui-list-item %)) items)))

(def ui-list-page (prim/factory ListPage {:keyfn :page/number}))

(defsc LargeList [this {:keys [list/current-page]}]
  {:initial-state (fn [params] {:list/current-page (prim/get-initial-state ListPage {})})
   :query         [{:list/current-page (prim/get-query ListPage)}]
   :ident         (fn [] [:list/by-id 1])}
  (let [{:keys [page/number]} current-page]
    (dom/div nil
      (dom/button #js {:disabled (= 1 number) :onClick #(prim/transact! this `[(goto-page {:page-number ~(dec number)})])} "Prior Page")
      (dom/button #js {:onClick #(prim/transact! this `[(goto-page {:page-number ~(inc number)})])} "Next Page")
      (ui-list-page current-page))))

(def ui-list (prim/factory LargeList))

(defsc Root [this {:keys [ui/react-key pagination/list] :or {ui/react-key "ROOT"}}]
  {:initial-state (fn [params] {:pagination/list (prim/get-initial-state LargeList {})})
   :query         [:ui/react-key {:pagination/list (prim/get-query LargeList)}]}
  (dom/div #js {:key react-key} (ui-list list)))

(defn initialize
  "To be used as started-callback. Load the first page."
  [{:keys [reconciler]}]
  (prim/transact! reconciler `[(goto-page {:page-number 1})]))
