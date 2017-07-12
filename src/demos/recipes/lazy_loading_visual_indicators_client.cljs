(ns recipes.lazy-loading-visual-indicators-client
  (:require
    [fulcro.client.core :as uc]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [om.dom :as dom]
    [om.next :as om :refer [defui]]))

(def initial-state {:ui/react-key "abc"
                    :panel        {}})

(defonce app (atom (uc/new-fulcro-client :initial-state initial-state)))

(declare Item)

(defui ^:once Item
  static om/IQuery
  ;; The :ui/fetch-state is queried so the parent (Child in this case) lazy load renderer knows what state the load is in
  (query [this] [:db/id :item/label :ui/fetch-state])
  static om/Ident
  (ident [this props] [:lazy-load.items/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [db/id item/label ui/fetch-state] :as props} (om/props this)]
      (dom/div nil label
        ; If an item is rendered, and the fetch state is present, you can use helper functions from df namespace
        ; to provide augmented rendering.
        (if (df/loading? fetch-state)
          (dom/span nil " (reloading...)")
          ; the `refresh!` function is a helper that can send an ident-based join query for a component.
          ; it is equivalent to `(load reconciler [:lazy-load.items/by-id ID] Item)`, but finds the params
          ; using the component itself.
          (dom/button #js {:onClick #(df/refresh! this)} "Refresh"))))))

(def ui-item (om/factory Item {:keyfn :db/id}))

(defui ^:once Child
  static om/IQuery
  ;; The :ui/fetch-state is queried so the parent (Panel) lazy load renderer knows what state the load is in
  (query [this] [:ui/fetch-state :child/label {:items (om/get-query Item)}])
  static om/Ident
  (ident [this props] [:lazy-load/ui :child])
  Object
  (render [this]
    (let [{:keys [child/label items] :as props} (om/props this)
          ; NOTE: Demostration of two ways of showing an item is refreshing...
          render-item (fn [idx i] (if (= idx 0)
                                    (ui-item i) ; use the childs method of showing refresh
                                    (df/lazily-loaded ui-item i))) ; replace child with a load marker
          render-list (fn [items] (map-indexed render-item items))]
      (dom/div nil
        (dom/p nil "Child Label: " label)
        ; Rendering for all of the states can be supplied to lazily-loaded as named parameters
        (df/lazily-loaded render-list items
          :not-present-render (fn [items] (dom/button #js {:onClick #(df/load-field this :items)} "Load Items")))))))

(def ui-child (om/factory Child))

(defui ^:once Panel
  static uc/InitialAppState
  (initial-state [c params] {:child nil})
  static om/IQuery
  (query [this] [[:ui/loading-data '_] {:child (om/get-query Child)}])
  static om/Ident
  (ident [this props] [:lazy-load/ui :panel])
  Object
  (render [this]
    (let [{:keys [ui/loading-data child] :as props} (om/props this)]
      (dom/div nil
        (dom/div #js {:style #js {:float "right" :display (if loading-data "block" "none")}} "GLOBAL LOADING")
        (dom/div nil "This is the Panel")
        (df/lazily-loaded ui-child child
          :not-present-render (fn [_] (dom/button #js {:onClick #(df/load-field this :child)} "Load Child")))))))

(def ui-panel (om/factory Panel))

; Note: Kinda hard to do idents/lazy loading right on root...so generally just have root render a div
; and then render a child that has the rest.
(defui ^:once Root
  static uc/InitialAppState
  (initial-state [c params] {:ui/react-key "A" :panel (uc/get-initial-state Panel nil)})
  static om/IQuery
  (query [this] [:ui/loading-data :ui/react-key {:panel (om/get-query Panel)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key panel] :or {ui/react-key "ROOT"} :as props} (om/props this)]
      (dom/div #js {:key react-key} (ui-panel panel)))))
