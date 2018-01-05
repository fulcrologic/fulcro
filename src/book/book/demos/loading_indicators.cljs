(ns book.demos.loading-indicators
  (:require
    [fulcro.client.dom :as dom]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
    [fulcro.client :as fc]
    [fulcro.server :as server]
    [fulcro.client.primitives :as prim :refer [defsc]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(server/defquery-entity :lazy-load/ui
  (value [env id params]
    (case id
      :panel {:child {:db/id 5 :child/label "Child"}}
      :child {:items [{:db/id 1 :item/label "A"} {:db/id 2 :item/label "B"}]}
      nil)))

(server/defquery-entity :lazy-load.items/by-id
  (value [env id params]
    (log/info "Item query for " id)
    {:db/id id :item/label (str "Refreshed Label " (rand-int 100))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLIENT:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def initial-state {:ui/react-key "abc"
                    :panel        {}})

(defonce app (atom (fc/new-fulcro-client :initial-state initial-state)))

(declare Item)

(defsc Item [this {:keys [db/id item/label] :as props}]
  ; query for the entire load marker table. use the lambda form of query for link queries
  {:query (fn [] [:db/id :item/label [df/marker-table '_]])
   :ident (fn [] [:lazy-load.items/by-id id])}
  (let [marker-id (keyword "item-marker" (str id))
        marker    (get-in props [df/marker-table marker-id])]
    (dom/div nil label
      ; If an item is rendered, and the fetch state is present, you can use helper functions from df namespace
      ; to provide augmented rendering.
      (if (df/loading? marker)
        (dom/span nil " (reloading...)")
        ; the `refresh!` function is a helper that can send an ident-based join query for a component.
        ; it is equivalent to `(load reconciler [:lazy-load.items/by-id id] Item)`, but finds the params
        ; using the component itself.
        (dom/button #js {:onClick #(df/refresh! this {:marker marker-id})} "Refresh")))))

(def ui-item (prim/factory Item {:keyfn :db/id}))

(defsc Child [this {:keys [child/label items] :as props}]
  {:query [:child/label {:items (prim/get-query Item)}]
   :ident (fn [] [:lazy-load/ui :child])}
  (let [render-list (fn [items] (map ui-item items))]
    (dom/div nil
      (dom/p nil "Child Label: " label)
      (if (seq items)
        (map ui-item items)
        (dom/button #js {:onClick #(df/load-field this :items :marker :child-marker)} "Load Items")))))

(def ui-child (prim/factory Child {:keyfn :child/label}))

(defsc Panel [this {:keys [ui/loading-data child] :as props}]
  {:initial-state (fn [params] {:child nil})
   :query         (fn [] [[:ui/loading-data '_] [df/marker-table '_] {:child (prim/get-query Child)}]) ; link querys require lambda
   :ident         (fn [] [:lazy-load/ui :panel])}
  (let [markers (get props df/marker-table)
        marker  (get markers :child-marker)]
    (dom/div nil
      (dom/div #js {:style #js {:float "right" :display (if loading-data "block" "none")}} "GLOBAL LOADING")
      (dom/div nil "This is the Panel")
      (if marker
        (dom/h4 nil "Loading child...")
        (if child
          (ui-child child)
          (dom/button #js {:onClick #(df/load-field this :child :marker :child-marker)} "Load Child"))))))

(def ui-panel (prim/factory Panel))

; Note: Kinda hard to do idents/lazy loading right on root...so generally just have root render a div
; and then render a child that has the rest.
(defsc Root [this {:keys [ui/react-key panel] :or {ui/react-key "ROOT"} :as props}]
  {:initial-state (fn [params] {:ui/react-key "A" :panel (prim/get-initial-state Panel nil)})
   :query         [:ui/react-key {:panel (prim/get-query Panel)}]}
  (dom/div #js {:key react-key} (ui-panel panel)))


