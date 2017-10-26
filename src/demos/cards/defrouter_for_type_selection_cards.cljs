(ns cards.defrouter-for-type-selection-cards
  (:require [fulcro.client.dom :as dom]
            [recipes.defrouter-for-type-selection :as dr]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.cards :refer [defcard-fulcro]]))

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
  (dc/mkdn-pprint-source dr/item-ident)
  (dc/mkdn-pprint-source dr/item-key)
  (dc/mkdn-pprint-source dr/make-person)
  (dc/mkdn-pprint-source dr/make-place)
  (dc/mkdn-pprint-source dr/make-thing)

  (dc/mkdn-pprint-source dr/PersonListItem)
  (dc/mkdn-pprint-source dr/ui-person)
  (dc/mkdn-pprint-source dr/PlaceListItem)
  (dc/mkdn-pprint-source dr/ui-place)
  (dc/mkdn-pprint-source dr/ThingListItem)
  (dc/mkdn-pprint-source dr/ui-thing)

  (dc/mkdn-pprint-source dr/ItemUnion)
  (dc/mkdn-pprint-source dr/ItemList)

  (dc/mkdn-pprint-source dr/PersonDetail)
  (dc/mkdn-pprint-source dr/PlaceDetail)
  (dc/mkdn-pprint-source dr/ThingDetail)

  "
  ```
  (defrouter ItemDetail :detail-router
    (ident [this props] (item-ident props))
    :person PersonDetail
    :place PlaceDetail
    :thing ThingDetail)

  (def ui-item-detail (om/factory ItemDetail))
  ```
  "
  (dc/mkdn-pprint-source dr/DemoRoot)

  )

(defcard-fulcro demo-card
  dr/DemoRoot
  {}
  {:inspect-data true})
