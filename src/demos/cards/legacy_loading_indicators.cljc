(ns cards.legacy-loading-indicators
  (:require
    [devcards.core :as dc :include-macros true]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [fulcro.client.dom :as dom]
    [fulcro.client.impl.protocols :as p]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
    [fulcro.client.core :as fc]
    [fulcro.server :as server]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [cards.card-utils :refer [sleep]]
    [fulcro.client.primitives :as prim :refer [defui defsc]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(server/defquery-entity :lazy-load/ui
  (value [env id params]
    (sleep 2000)
    (case id
      :panel {:child {:db/id 5 :child/label "Child"}}
      :child {:items [{:db/id 1 :item/label "A"} {:db/id 2 :item/label "B"}]}
      nil)))

(server/defquery-entity :lazy-load.items/by-id
  (value [env id params]
    (log/info "Item query for " id)
    (sleep 4000)
    {:db/id id :item/label (str "Refreshed Label " (rand-int 100))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLIENT:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
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
  static prim/InitialAppState
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
  static prim/InitialAppState
  (initial-state [c params] {:ui/react-key "A" :panel (prim/get-initial-state Panel nil)})
  static prim/IQuery
  (query [this] [:ui/react-key {:panel (prim/get-query Panel)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key panel] :or {ui/react-key "ROOT"} :as props} (prim/props this)]
      (dom/div #js {:key react-key} (ui-panel panel)))))


#?(:cljs
   (dc/defcard-doc
     "# Lazy Load Indicators (Legacy)

     NOTE: Fulcro 2.0 has an improved version of this. See the loading-indicators demo and Developer's Guide.

     Fulcro places markers on items that are being loaded. These markers can be used to show progress indicators in
     the UI. There are essentially two kinds: a global marker, and an item-based marker. The global marker is present during
     and loads, whereas the localized markers are present until a specific item's load has completed.

     The comments in the code below describe how to use these:
     "
     (dc/mkdn-pprint-source Item)
     (dc/mkdn-pprint-source Child)
     (dc/mkdn-pprint-source Root)))

#?(:cljs
   (defcard-fulcro lazy-loading-demo
     "
     # Demo

     This is a full-stack demo, and requires you run the server (see demo instructions).

     The first button triggers a load of a child's data from the server. There is a built-in delay of 1 second so you
     can see the markers. Once the child is loaded, a button appears indicating items can be loaded into that child. The
     same 1 second delay is present so you can see the markers.

     Once the items are loaded, each has a refresh button. Again, a 1 second delay is present so you can examine the
     markers.

     The app state is shown so you can see the marker detail appear/disappear. In general you'll use the `lazily-loaded`
     helper to render different load states, and you should not base you code on the internal details of the load marker data.

     Note that once you get this final items loaded (which have refresh buttons), the two items have different ways of
     showing refresh.
     "
     Root
     {}
     {:inspect-data true}))

