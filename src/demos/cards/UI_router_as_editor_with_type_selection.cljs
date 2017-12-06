(ns cards.UI-router-as-editor-with-type-selection
  (:require [fulcro.client.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.routing :as r :refer [defrouter]]
            [fulcro.client.primitives :as prim :refer [defsc]]
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

(defsc PersonDetail [this {:keys [db/id person/name] :as props}]
  ; defrouter expects there to be an initial state for each possible target. We'll cause this to be a "no selection"
  ; state so that the detail screen that starts out will show "Nothing selected". We initialize all three in case
  ; we later re-order them in the defrouter.
  {:ident         (fn [] (item-ident props))
   :query         [:kind :db/id :person/name]
   :initial-state {:db/id :no-selection :kind :person}}
  (dom/div nil
    (if (= id :no-selection)
      "Nothing selected"
      (str "Details about person " name))))

(defsc PlaceDetail [this {:keys [db/id place/name] :as props}]
  {:ident         (fn [] (item-ident props))
   :query         [:kind :db/id :place/name]
   :initial-state {:db/id :no-selection :kind :place}}
  (dom/div nil
    (if (= id :no-selection)
      "Nothing selected"
      (str "Details about place " name))))

(defsc ThingDetail [this {:keys [db/id thing/label] :as props}]
  {:ident         (fn [] (item-ident props))
   :query         [:kind :db/id :thing/label]
   :initial-state {:db/id :no-selection :kind :thing}}
  (dom/div nil
    (if (= id :no-selection)
      "Nothing selected"
      (str "Details about thing " label))))

(defsc PersonListItem [this
                       {:keys [db/id person/name] :as props}
                       {:keys [onSelect] :as computed}]
  {:ident (fn [] (item-ident props))
   :query [:kind :db/id :person/name]}
  (dom/li #js {:onClick #(onSelect (item-ident props))}
    (dom/a #js {:href "javascript:void(0)"} (str "Person " id " " name))))

(def ui-person (prim/factory PersonListItem {:keyfn item-key}))

(defsc PlaceListItem [this {:keys [db/id place/name] :as props} {:keys [onSelect] :as computed}]
  {:ident (fn [] (item-ident props))
   :query [:kind :db/id :place/name]}
  (dom/li #js {:onClick #(onSelect (item-ident props))}
    (dom/a #js {:href "javascript:void(0)"} (str "Place " id " : " name))))

(def ui-place (prim/factory PlaceListItem {:keyfn item-key}))

(defsc ThingListItem [this {:keys [db/id thing/label] :as props} {:keys [onSelect] :as computed}]
  {:ident (fn [] (item-ident props))
   :query [:kind :db/id :thing/label]}
  (dom/li #js {:onClick #(onSelect (item-ident props))}
    (dom/a #js {:href "javascript:void(0)"} (str "Thing " id " : " label))))

(def ui-thing (prim/factory ThingListItem item-key))

(defrouter ItemDetail :detail-router
  (ident [this props] (item-ident props))
  :person PersonDetail
  :place PlaceDetail
  :thing ThingDetail)

(def ui-item-detail (prim/factory ItemDetail))

(defsc ItemUnion [this {:keys [kind] :as props}]
  {:ident (fn [] (item-ident props))
   :query (fn [] {:person (prim/get-query PersonListItem)
                  :place  (prim/get-query PlaceListItem)
                  :thing  (prim/get-query ThingListItem)})}
  (case kind
    :person (ui-person props)
    :place (ui-place props)
    :thing (ui-thing props)))

(def ui-item-union (prim/factory ItemUnion {:keyfn item-key}))

(defsc ItemList [this {:keys [items]} {:keys [onSelect]}]
  {
   :initial-state (fn [p]
                    ; These would normally be loaded...but for demo purposes we just hand code a few
                    {:items [(make-person 1 "Tony")
                             (make-thing 2 "Toaster")
                             (make-place 3 "New York")
                             (make-person 4 "Sally")
                             (make-thing 5 "Pillow")
                             (make-place 6 "Canada")]})
   :ident         (fn [] [:lists/by-id :singleton])
   :query         [{:items (prim/get-query ItemUnion)}]}
  (dom/ul nil
    (map (fn [i] (ui-item-union (prim/computed i {:onSelect onSelect}))) items)))

(def ui-item-list (prim/factory ItemList))

(defsc DemoRoot [this {:keys [item-list item-detail]}]
  {:query         [{:item-list (prim/get-query ItemList)}
                   {:item-detail (prim/get-query ItemDetail)}]
   :initial-state (fn [p] (merge
                            (r/routing-tree
                              (r/make-route :detail [(r/router-instruction :detail-router [:param/kind :param/id])]))
                            {:item-list   (prim/get-initial-state ItemList nil)
                             :item-detail (prim/get-initial-state ItemDetail nil)}))}
  (let [; This is the only thing to do: Route the to the detail screen with the given route params!
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
            (b/col {:xs 6} (ui-item-detail item-detail))))))))

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
