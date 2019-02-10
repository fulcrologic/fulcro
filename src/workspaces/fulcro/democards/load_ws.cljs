(ns fulcro.democards.load-ws
  (:require
    [nubank.workspaces.core :as ws]
    [nubank.workspaces.model :as wsm]
    [nubank.workspaces.card-types.fulcro :as ct.fulcro]
    [nubank.workspaces.lib.fulcro-portal :as f.portal]
    [fulcro.client :as fc]
    [fulcro.server :as server]
    [fulcro.client.primitives :as prim :refer [defui defsc]]
    [fulcro.client.dom :as dom]
    [fulcro.client.network :as net]
    [fulcro.client.mutations :as m]
    [fulcro.client.data-fetch :as df]))

(defrecord MockNetwork []
  net/FulcroNetwork
  (send [this edn done-callback error-callback]
    (js/setTimeout (fn []
                     (if (= 'fulcro.client.load-cards/add-thing (ffirst edn))
                       (let [tempid (-> edn first second :id)]
                         (done-callback {'fulcro.client.load-cards/add-thing {:tempids {tempid 1010}}}))
                       (done-callback {[:thing/by-id 1010] {:id 1010 :label "B"}}))) 2000))
  (start [this] this))

(defui Thing
  static prim/IQuery
  (query [this] [:id :label])
  static prim/Ident
  (ident [this props] [:thing/by-id (:id props)])
  Object
  (render [this] (dom/div "THING")))

(defui Root
  Object
  (render [this]
    (dom/div "TODO")))

(m/defmutation add-thing
  "Read a thing"
  [{:keys [id label]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [:thing/by-id id] {:id id :label label}))
  (remote [env] true))

#_"# Sequential Processing

  This card does a write (with a tempid) and a re-read of that as a follow-on read. This
  exercises:

  - tempid rewrites in the network queue
    - The initial entity has a tempid (generated)
    - The tempid is rewritten (to 1010)
    - The follow-on read reads the correct thing (label should update to B)
  - follow-on reads from a remote
  - load marker placement and removal
    - Should see a load marker appear IN the entity
    - Should see no load markers at the end
  "
(ws/defcard load-with-follow-on
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root       Root
     ::f.portal/app        {:networking       (MockNetwork.)
                            :started-callback (fn [{:keys [reconciler]}]
                                                (let [id (prim/tempid)]
                                                  (prim/transact! reconciler `[(add-thing {:id ~id :label "A"})])
                                                  (df/load reconciler [:thing/by-id id] Thing)))}
     ::f.portal/wrap-root? false}))


(defrecord MockNetForMerge []
  net/FulcroNetwork
  (send [this edn done-callback error-callback]
    (js/setTimeout (fn []
                     (cond
                       (= [{:thing (prim/get-query Thing)}] edn) (done-callback {:thing {:id 2 :label "UPDATED B"}})
                       :else (done-callback {[:thing/by-id 1] {:id 1 :label "UPDATED A"}}))) 500))
  (start [this] this))

#_"# Merging

  This card loads over both a non-normalized item, and entry that is normalized from a tree response,
  and an entry that is refreshed by ident. In all cases, the (non-queried) UI attributes should remain.

  - Thing 1 and 2 should still have a :ui/value
  - Thing 1 and 2 should end up with UPDATED labels

  Basic final state should be:

  ```
  {:thing/by-id {1 {:id 1 :label \"UPDATED A\" :ui/value 1}
                 2 {:id 2 :label \"UPDATED B\" :ui/value 2}
                 3 {:id 3 :label \"C\" :ui/value 3}}
   :thing       [:thing/by-id 2]}
  ```
  "
(ws/defcard ui-attr-merge
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root       Root
     ::f.portal/app
                           {:started-callback (fn [{:keys [reconciler]}]
                                                (js/setTimeout #(df/load reconciler [:thing/by-id 1] Thing {:refresh [[:fake 1] :no-prop] :without #{:ui/value}}) 100)
                                                (js/setTimeout #(df/load reconciler :thing Thing {:without #{:ui/value}}) 200))
                            :initial-state    (atom {:thing/by-id {1 {:id 1 :label "A" :ui/value 1}
                                                                   2 {:id 2 :label "B" :ui/value 2}
                                                                   3 {:id 3 :label "C" :ui/value 3}}})
                            :networking       (MockNetForMerge.)}
     ::f.portal/wrap-root? false}))

(defsc FocusA [_ _]
  {:query [:i :have :data]})

(defsc FocusB [_ _]
  {:query [:x :y]})

(defsc FocusRoot [_ _]
  {:query [{:a (prim/get-query FocusA)}
           {:b (prim/get-query FocusB)}]})

(def echo-parser
  (prim/parser {:read (fn [{:keys [ast parser]} _ _]
                        (if-let [q (:query ast)]
                          {:value (parser {} q)}
                          {:value (str (:key ast))}))}))

(defrecord MockNetForEcho []
  net/FulcroNetwork
  (send [this edn done-callback error-callback]
    (js/setTimeout (fn []
                     (done-callback (echo-parser {} edn))) 500))
  (start [this] this))

#_"# Focusing

  ```
  {:root {:a {:data \":data\"}
          :b {:x \":x\" :y \":y\"}}}
  ```
  "
(ws/defcard ui-load-focus
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root       FocusRoot
     ::f.portal/app        {:started-callback (fn [{:keys [reconciler]}]
                                                (js/setTimeout #(df/load reconciler :root FocusRoot {:focus [{:a [:data]} :b]}) 100))
                            :networking       (MockNetForEcho.)}
     ::f.portal/wrap-root? false}))

(defsc DupeKeyFetch [this {:keys [db/id a b c] :as props}]
  {:query         [:db/id :a :b :c]
   :ident         [:dupe-key-fetch/by-id :db/id]
   :initial-state {:db/id 1}}
  (dom/div (str a) (str b) (str c)))

(def ui-dupe-key-fetch (prim/factory DupeKeyFetch {:keyfn :db/id}))

(server/defquery-root ::x
  (value [env params]
    (js/console.log :FETCH ::X)
    {:value (rand-int 100)}))

(ws/defcard dupe-key-fetch
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root       DupeKeyFetch
     ::f.portal/app        {:networking       (server/new-server-emulator)
                            :started-callback (fn [app]
                                                (df/load app ::x nil {:target [:T 1 :a]})
                                                (df/load app ::x nil {:target [:T 1 :b]})
                                                (df/load app ::x nil {:target [:T 1 :c]}))}
     ::f.portal/wrap-root? true}))

(defsc Item [_ _]
  {:initial-state (fn [_] {:id "sample"})
   :ident         [:id :id]
   :query         [:id :name]}
  (dom/div "Item"))

(ws/defcard vector-marker
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root       Item
     ::f.portal/app        {:networking       (MockNetForEcho.)
                            :started-callback (fn [app]
                                                (df/load app [:id "sample"] Item {:marker [:id "sample"]}))}
     ::f.portal/wrap-root? true}))
