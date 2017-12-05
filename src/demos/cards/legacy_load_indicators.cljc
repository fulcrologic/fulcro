(ns cards.legacy-load-indicators
  (:require
    [fulcro.client :as fc]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.server :refer [defquery-entity]]
    [fulcro.client.dom :as dom]
    #?@(:clj  [
    [taoensso.timbre :as timbre]]
        :cljs [[devcards.core :as dc :include-macros true]
               [fulcro.client.cards :refer [defcard-fulcro]]])))

;; SERVER

(defquery-entity :lazy-load/ui
  (value [env id params]
    #?(:clj (Thread/sleep 1000))
    (case id
      :panel {:child {:db/id 5 :child/label "Child"}}
      :child {:items [{:db/id 1 :item/label "A"} {:db/id 2 :item/label "B"}]}
      nil)))

(defquery-entity :lazy-load.items/by-id
  (value [env id params]
    #?(:clj (timbre/info "Item query for " id))
    #?(:clj (Thread/sleep 1000))
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

#?(:cljs
   (dc/defcard-doc
     "# Lazy Load Indicators

     NOTE: Fulcro 2.0 has better support. This is still available, but see also loading-indicators in the demos.

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

