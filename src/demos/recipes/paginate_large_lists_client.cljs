(ns recipes.paginate-large-lists-client
  (:require
    [untangled.client.core :as uc]
    [untangled.client.data-fetch :as df]
    [untangled.client.mutations :as m]
    [om.dom :as dom]
    [om.next :as om :refer [defui]]))

(defui ^:once ListItem
  static om/IQuery
  (query [this] [:item/id])
  static om/Ident
  (ident [this props] [:items/by-id (:item/id props)])
  Object
  (render [this]
    (dom/li nil (str "Item " (-> this om/props :item/id)))))

(def ui-list-item (om/factory ListItem {:keyfn :item/id}))

(defui ^:once LargeList
  static om/IQuery
  (query [this] [:start :total-results {:items (om/get-query ListItem)}])
  Object
  (render [this]
    (let [{:keys [start total-results items]} (om/props this)]
      (dom/div nil
        (dom/h2 nil (str "Items " start "+ (of " total-results ")"))
        (dom/button #js {:onClick #(om/transact! this '[(prior-page) (fill-cache)])} "Prior Page")
        (dom/button #js {:onClick #(om/transact! this '[(next-page) (fill-cache)])} "Next Page")
        (mapv ui-list-item items)))))


(def ui-list (om/factory LargeList))

(defui ^:once Root
  static om/IQuery
  (query [this] [:ui/react-key {:current-page (om/get-query LargeList)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key current-page] :or {ui/react-key "ROOT"}} (om/props this)]
      (dom/div #js {:key react-key} (ui-list current-page)))))

(defn populate-items
  "A helper function that returns recipes.a new state map where the correct items are copied from the cache into the current page-client"
  [state-map]
  (let [start (-> state-map :current-page :start)
        pg    (-> state-map :current-page :page-size)
        items (->> state-map :list-cache (drop start) (take pg) vec)]
    (assoc-in state-map [:current-page :items] items)))

(defn prior-page
  "A helper function to update the app state map to show the prior page. Does not trigger cache filling (items will end up
  empty if not already loaded)."
  [state-map]
  (let [pg (get-in state-map [:current-page :page-size])]
    (-> state-map
      (update-in [:current-page :start] (comp (partial max 0) #(- % pg)))
      populate-items)))

(defn next-page
  "A helper function to update the app state map to show the next page. Does not trigger cache filling (items will end up
  empty if not already loaded)."
  [state-map]
  (let [pg      (get-in state-map [:current-page :page-size])
        last-pg (- (get-in state-map [:current-page :total-results]) pg)]
    (-> state-map
      (update-in [:current-page :start] (comp (partial min last-pg) (partial + pg)))
      populate-items)))

(defn page-query
  "A helper function that generates the proper server query for loading a range of items. The result of this load will
  go in app state at :load-page. A post mutation is used to then fill the cache and populate the visible page."
  [start end]
  `[({:load-page [:start :end
                  {:items ~(om/get-query ListItem)}]} {:start ~start :end ~end})])

(defn populate-cache
  "A helper function that moves the loaded cache items into the cache and populates on-screen page (if possible)"
  [state-map]
  ;; NOTE: very naive, assumes you are doing linear page movement, not jumping all-at-once.
  (let [loaded-start (-> state-map :load-page :start)
        items        (-> state-map :load-page :items)
        old-head     (->> state-map :list-cache (take loaded-start) vec)
        new-cache    (concat old-head items)]
    (-> state-map
      (assoc :list-cache new-cache)
      ; get rid of the temporary query response
      (dissoc :load-page)
      ; Copy the cache into the ui page to make sure it is shown
      populate-items)))

; A post-mutation to trigger the cache and view population from the server response
(defmethod m/mutate 'page-loaded [{:keys [state]} k p] {:action #(swap! state populate-cache)})

; A mutation that proxies into the next-page helper function
(defmethod m/mutate 'next-page [{:keys [state]} k p]
  {:action #(swap! state next-page)})

; A mutation that proxies into the prior-page helper function
(defmethod m/mutate 'prior-page [{:keys [state]} k p]
  {:action #(swap! state prior-page)})

; A mutation that triggers a remote load of some range of cache items
(defmethod m/mutate 'fill-cache [{:keys [state] :as env} k p]
  (let [start (-> @state :list-cache count)
        pg    (-> @state :current-page :page-size)
        end   (-> @state :current-page :start (+ pg))]
    (when (> end start)
      {:remote (df/remote-load env)
       :action #(df/load-action state :items ListItem {:params ~{:start         start :end end
                                                                 :post-mutation 'page-loaded :refresh [:items]}})})))
(def initial-state {:ui/react-key "abc"
                    ; The items that are loaded...naive implementation, loaded from 0, append as we go
                    :list-cache   []
                    ; Tracking where we are. This is a UI data structure. :items populated from cache
                    :current-page {:page-size     10
                                   :total-results 200000
                                   :start         0
                                   :items         []}})

(defonce app (atom (uc/new-untangled-client
                     :initial-state initial-state
                     :started-callback
                     (fn [{:keys [reconciler]}]
                       (df/load reconciler :items ListItem {:params        {:start 0 :end 10}
                                                            :post-mutation 'page-loaded
                                                            :refresh       [:items]})))))

