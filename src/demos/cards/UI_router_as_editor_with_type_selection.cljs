(ns cards.UI-router-as-editor-with-type-selection
  (:require [fulcro.client.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.routing :as r :refer [defrouter]]
            [fulcro.client.primitives :as prim :refer [defui defsc]]
            [fulcro.client :as fc]
            [fulcro.ui.bootstrap3 :as b]
            [fulcro.ui.elements :as ele]
            [fulcro.client.cards :refer [defcard-fulcro]]))

(defn item-ident
  "Generate an ident from a person, place, or thing."
  [props] [(:kind props) (:db/id props)])

(defn item-key
  "Generate a distinct react key for a person, place, or thing"
  [props] (str (:kind props) "-" (:db/id props)))

(defn make-person [id n] {:db/id id :kind :person :person/name n})
(defn make-place [id n] {:db/id id :kind :place :place/name n})
(defn make-thing [id n] {:db/id id :kind :thing :thing/label n})

(defui ^:once PersonDetail
  static prim/Ident
  (ident [this props] (item-ident props))
  static prim/IQuery
  (query [this] [:kind :db/id :person/name])
  ; defrouter expects there to be an initial state for each possible target. We'll cause this to be a "no selection"
  ; state so that the detail screen that starts out will show "Nothing selected". We initialize all three in case
  ; we later re-order them in the defrouter.
  static prim/InitialAppState
  (initial-state [c p] {:db/id :no-selection :kind :person})
  Object
  (render [this]
    (let [{:keys [db/id person/name]} (prim/props this)]
      (dom/div nil
        (if (= id :no-selection)
          "Nothing selected"
          (str "Details about person " name))))))

(defui ^:once PlaceDetail
  static prim/Ident
  (ident [this props] (item-ident props))
  static prim/IQuery
  (query [this] [:kind :db/id :place/name])
  static prim/InitialAppState
  (initial-state [c p] {:db/id :no-selection :kind :place})
  Object
  (render [this]
    (let [{:keys [db/id place/name]} (prim/props this)]
      (dom/div nil
        (if (= id :no-selection)
          "Nothing selected"
          (str "Details about place " name))))))

(defui ^:once ThingDetail
  static prim/Ident
  (ident [this props] (item-ident props))
  static prim/IQuery
  (query [this] [:kind :db/id :thing/label])
  static prim/InitialAppState
  (initial-state [c p] {:db/id :no-selection :kind :thing})
  Object
  (render [this]
    (let [{:keys [db/id thing/label]} (prim/props this)]
      (dom/div nil
        (if (= id :no-selection)
          "Nothing selected"
          (str "Details about thing " label))))))

(defui ^:once PersonListItem
  static prim/Ident
  (ident [this props] (item-ident props))
  static prim/IQuery
  (query [this] [:kind :db/id :person/name])
  Object
  (render [this]
    (let [{:keys [db/id person/name] :as props} (prim/props this)
          onSelect (prim/get-computed this :onSelect)]
      (dom/li #js {:onClick #(onSelect (item-ident props))}
        (dom/a #js {:href "javascript:void(0)"} (str "Person " id " " name))))))

(def ui-person (prim/factory PersonListItem {:keyfn item-key}))

(defui ^:once PlaceListItem
  static prim/Ident
  (ident [this props] (item-ident props))
  static prim/IQuery
  (query [this] [:kind :db/id :place/name])
  Object
  (render [this]
    (let [{:keys [db/id place/name] :as props} (prim/props this)
          onSelect (prim/get-computed this :onSelect)]
      (dom/li #js {:onClick #(onSelect (item-ident props))}
        (dom/a #js {:href "javascript:void(0)"} (str "Place " id " : " name))))))

(def ui-place (prim/factory PlaceListItem {:keyfn item-key}))

(defui ^:once ThingListItem
  static prim/Ident
  (ident [this props] (item-ident props))
  static prim/IQuery
  (query [this] [:kind :db/id :thing/label])
  Object
  (render [this]
    (let [{:keys [db/id thing/label] :as props} (prim/props this)
          onSelect (prim/get-computed this :onSelect)]
      (dom/li #js {:onClick #(onSelect (item-ident props))}
        (dom/a #js {:href "javascript:void(0)"} (str "Thing " id " : " label))))))

(def ui-thing (prim/factory ThingListItem item-key))

(defrouter ItemDetail :detail-router
  (ident [this props] (item-ident props))
  :person PersonDetail
  :place PlaceDetail
  :thing ThingDetail)

(def ui-item-detail (prim/factory ItemDetail))

(defui ^:once ItemUnion
  static prim/Ident
  (ident [this props] (item-ident props))
  static prim/IQuery
  (query [this] {:person (prim/get-query PersonListItem)
                 :place  (prim/get-query PlaceListItem)
                 :thing  (prim/get-query ThingListItem)})
  Object
  (render [this]
    (let [{:keys [kind] :as props} (prim/props this)]
      (case kind
        :person (ui-person props)
        :place (ui-place props)
        :thing (ui-thing props)))))

(def ui-item-union (prim/factory ItemUnion {:keyfn item-key}))

(defui ^:once ItemList
  static prim/InitialAppState
  (initial-state [c p]
    ; These would normally be loaded...but for demo purposes we just hand code a few
    {:items [(make-person 1 "Tony")
             (make-thing 2 "Toaster")
             (make-place 3 "New York")
             (make-person 4 "Sally")
             (make-thing 5 "Pillow")
             (make-place 6 "Canada")]})
  static prim/Ident
  (ident [this props] [:lists/by-id :singleton])
  static prim/IQuery
  (query [this] [{:items (prim/get-query ItemUnion)}])
  Object
  (render [this]
    (let [{:keys [items]} (prim/props this)
          onSelect (prim/get-computed this :onSelect)]
      (dom/ul nil
        (map (fn [i] (ui-item-union (prim/computed i {:onSelect onSelect}))) items)))))

(def ui-item-list (prim/factory ItemList))

(defui ^:once DemoRoot
  static prim/IQuery
  (query [this] [{:item-list (prim/get-query ItemList)}
                 {:item-detail (prim/get-query ItemDetail)}])
  static prim/InitialAppState
  (initial-state [c p] (merge
                         (r/routing-tree
                           (r/make-route :detail [(r/router-instruction :detail-router [:param/kind :param/id])]))
                         {:item-list   (prim/get-initial-state ItemList nil)
                          :item-detail (prim/get-initial-state ItemDetail nil)}))
  Object
  (render [this]
    (let [{:keys [item-list item-detail]} (prim/props this)
          ; This is the only thing to do: Route the to the detail screen with the given route params!
          showDetail (fn [[kind id]]
                       (prim/transact! this `[(r/route-to {:handler :detail :route-params {:kind ~kind :id ~id}})]))]
      ; devcards, embed in iframe so we can use bootstrap css easily
      (ele/ui-iframe {:frameBorder 0 :height "300px" :width "100%"}
        (dom/div #js {:key "example-frame-key"}
          (dom/style nil ".boxed {border: 1px solid black}")
          (dom/link #js {:rel "stylesheet" :href "bootstrap-3.3.7/css/bootstrap.min.css"})
          (b/container-fluid {}
            (b/row {}
              (b/col {:xs 6} "Items")
              (b/col {:xs 6} "Detail"))
            (b/row {}
              (b/col {:xs 6} (ui-item-list (prim/computed item-list {:onSelect showDetail})))
              (b/col {:xs 6} (ui-item-detail item-detail)))))))))

(defcard-doc
  "# Defrouter as a UI Type Selector

  The component defined by `defrouter` is really just a union component that can be switched to point at any kind
  of component that it knows about. The support for parameterized routers in the routing tree makes it possible
  to very easily reuse the UI router as a component that can show one of many screens in the same location.

  This is particularly useful when you have a list of items that have varying types, and you'd like to, for example,
  show the list on one side of the screen and the detail on the other.

  To write such a thing one would follow these steps:

  1. Create one component for each item type that represents how it will look in the list.
  2. Create one component for each item type that represents the fine detail view for that item.
  3. Join (1) together into a union component and use it in a component that shows them as a list. In other words
  the union will represent a to-many edge in your graph. Remember that unions cannot stand alone, so there
  will be a union component (to switch the UI) and a list component to iterate through the items.
  4. Combine the detail components from (2) into a `defrouter` (e.g. named :detail-router).
  5. Create a routing tree that includes the :detail-router, and parameterize both elements of the target ident (kind and id)
  6. Hook a click event from the items to a `route-to` mutation, and send route parameters for the kind and id.

  Here is the source for such a UI:
  "
  (dc/mkdn-pprint-source item-ident)
  (dc/mkdn-pprint-source item-key)
  (dc/mkdn-pprint-source make-person)
  (dc/mkdn-pprint-source make-place)
  (dc/mkdn-pprint-source make-thing)

  (dc/mkdn-pprint-source PersonListItem)
  (dc/mkdn-pprint-source ui-person)
  (dc/mkdn-pprint-source PlaceListItem)
  (dc/mkdn-pprint-source ui-place)
  (dc/mkdn-pprint-source ThingListItem)
  (dc/mkdn-pprint-source ui-thing)

  (dc/mkdn-pprint-source ItemUnion)
  (dc/mkdn-pprint-source ItemList)

  (dc/mkdn-pprint-source PersonDetail)
  (dc/mkdn-pprint-source PlaceDetail)
  (dc/mkdn-pprint-source ThingDetail)

  "
  ```
  (defrouter ItemDetail :detail-router
    (ident [this props] (item-ident props))
    :person PersonDetail
    :place PlaceDetail
    :thing ThingDetail)

  (def ui-item-detail (prim/factory ItemDetail))
  ```
  "
  (dc/mkdn-pprint-source DemoRoot))

(defcard-fulcro demo-card
  DemoRoot
  {}
  {:inspect-data true})
