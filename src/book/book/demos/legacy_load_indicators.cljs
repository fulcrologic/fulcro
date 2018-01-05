(ns book.demos.legacy-load-indicators
  (:require
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
    [fulcro.server :refer [defquery-entity]]
    [fulcro.client.dom :as dom]))

;; SERVER

(defquery-entity :lazy-load/ui
  (value [env id params]
    (case id
      :panel {:child {:db/id 5 :child/label "Child"}}
      :child {:items [{:db/id 1 :item/label "A"} {:db/id 2 :item/label "B"}]}
      nil)))

(defquery-entity :lazy-load.items/by-id
  (value [env id params]
    (log/info "Item query for " id)
    {:db/id id :item/label (str "Refreshed Label " (rand-int 100))}))

;; CLIENT

(declare Item)

(defsc Item [this {:keys [db/id item/label ui/fetch-state] :as props}]
  ;; The :ui/fetch-state is queried so the parent (Child in this case) lazy load renderer knows what state the load is in
  {:query [:db/id :item/label :ui/fetch-state]
   :ident [:lazy-load.items/by-id :db/id]}
  (dom/div nil label
    ; If an item is rendered, and the fetch state is present, you can use helper functions from df namespace
    ; to provide augmented rendering.
    (if (df/loading? fetch-state)
      (dom/span nil " (reloading...)")
      ; the `refresh!` function is a helper that can send an ident-based join query for a component.
      ; it is equivalent to `(load reconciler [:lazy-load.items/by-id ID] Item)`, but finds the params
      ; using the component itself.
      (dom/button #js {:onClick #(df/refresh! this)} "Refresh"))))

(def ui-item (prim/factory Item {:keyfn :db/id}))

(defsc Child [this {:keys [child/label items] :as props}]
  ;; The :ui/fetch-state is queried so the parent (Panel) lazy load renderer knows what state the load is in
  {:query [:ui/fetch-state :child/label {:items (prim/get-query Item)}]
   :ident (fn [] [:lazy-load/ui :child])}
  (let [; NOTE: Demostration of two ways of showing an item is refreshing...
        render-item (fn [idx i] (if (= idx 0)
                                  (ui-item i)               ; use the childs method of showing refresh
                                  (dom/span #js {:key (str "ll-" idx)} ; the span is so we have a react key in the list
                                    (df/lazily-loaded ui-item i)))) ; replace child with a load marker
        render-list (fn [items] (map-indexed render-item items))]
    (dom/div nil
      (dom/p nil "Child Label: " label)
      ; Rendering for all of the states can be supplied to lazily-loaded as named parameters
      (df/lazily-loaded render-list items
        :not-present-render (fn [items] (dom/button #js {:onClick #(df/load-field this :items)} "Load Items"))))))

(def ui-child (prim/factory Child {:keyfn :child/label}))

(defsc Panel [this {:keys [ui/loading-data child] :as props}]
  {:initial-state (fn [params] {:child nil})
   :query         (fn [] [[:ui/loading-data '_] {:child (prim/get-query Child)}])
   :ident         (fn [] [:lazy-load/ui :panel])}
  (dom/div nil
    (dom/div #js {:style #js {:float "right" :display (if loading-data "block" "none")}} "GLOBAL LOADING")
    (dom/div nil "This is the Panel")
    (df/lazily-loaded ui-child child
      :not-present-render (fn [_] (dom/button #js {:onClick #(df/load-field this :child)} "Load Child")))))

(def ui-panel (prim/factory Panel))

; Note: Kinda hard to do idents/lazy loading right on root...so generally just have root render a div
; and then render a child that has the rest.
(defsc Root [this {:keys [ui/react-key panel] :or {ui/react-key "ROOT"} :as props}]
  {:initial-state (fn [params] {:ui/react-key "A" :panel (prim/get-initial-state Panel nil)})
   :query         [:ui/loading-data :ui/react-key {:panel (prim/get-query Panel)}]}
  (dom/div #js {:key react-key} (ui-panel panel)))


