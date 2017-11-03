(ns recipes.lazy-loading-visual-indicators-client
  (:require
    [fulcro.client.core :as fc]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defui]]))

(def initial-state {:ui/react-key "abc"
                    :panel        {}})

(defonce app (atom (fc/new-fulcro-client :initial-state initial-state)))

(declare Item)

(defui ^:once Item
  static prim/IQuery
  (query [this] [:db/id :item/label [df/marker-table '_]])
  static prim/Ident
  (ident [this props] [:lazy-load.items/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [db/id item/label] :as props} (prim/props this)
          marker-id (keyword "item-marker" (str id))
          marker    (get-in props [df/marker-table marker-id])]
      (dom/div nil label
        ; If an item is rendered, and the fetch state is present, you can use helper functions from df namespace
        ; to provide augmented rendering.
        (if (df/loading? marker)
          (dom/span nil " (reloading...)")
          ; the `refresh!` function is a helper that can send an ident-based join query for a component.
          ; it is equivalent to `(load reconciler [:lazy-load.items/by-id id] Item)`, but finds the params
          ; using the component itself.
          (dom/button #js {:onClick #(df/refresh! this {:marker marker-id})} "Refresh"))))))

(def ui-item (prim/factory Item {:keyfn :db/id}))

(defui ^:once Child
  static prim/IQuery
  (query [this] [:child/label {:items (prim/get-query Item)}])
  static prim/Ident
  (ident [this props] [:lazy-load/ui :child])
  Object
  (render [this]
    (let [{:keys [child/label items] :as props} (prim/props this)
          render-list (fn [items] (map ui-item items))]
      (dom/div nil
        (dom/p nil "Child Label: " label)
        (if (seq items)
          (map ui-item items)
          (dom/button #js {:onClick #(df/load-field this :items :marker :child-marker)} "Load Items"))))))

(def ui-child (prim/factory Child {:keyfn :child/label}))

(defui ^:once Panel
  static fc/InitialAppState
  (initial-state [c params] {:child nil})
  static prim/IQuery
  (query [this] [[:ui/loading-data '_] [df/marker-table '_] {:child (prim/get-query Child)}])
  static prim/Ident
  (ident [this props] [:lazy-load/ui :panel])
  Object
  (render [this]
    (let [{:keys [ui/loading-data child] :as props} (prim/props this)
          markers (get props df/marker-table)
          marker  (get markers :child-marker)]
      (dom/div nil
        (dom/div #js {:style #js {:float "right" :display (if loading-data "block" "none")}} "GLOBAL LOADING")
        (dom/div nil "This is the Panel")
        (if marker
          (dom/h4 nil "Loading child...")
          (if child
            (ui-child child)
            (dom/button #js {:onClick #(df/load-field this :child :marker :child-marker)} "Load Child")))))))

(def ui-panel (prim/factory Panel))

; Note: Kinda hard to do idents/lazy loading right on root...so generally just have root render a div
; and then render a child that has the rest.
(defui ^:once Root
  static fc/InitialAppState
  (initial-state [c params] {:ui/react-key "A" :panel (fc/get-initial-state Panel nil)})
  static prim/IQuery
  (query [this] [:ui/react-key {:panel (prim/get-query Panel)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key panel] :or {ui/react-key "ROOT"} :as props} (prim/props this)]
      (dom/div #js {:key react-key} (ui-panel panel)))))
