(ns untangled.client.load-cards
  (:require
    [devcards.core :as dc :refer-macros [defcard]]
    [untangled.client.core :as uc]
    [untangled.client.cards :refer [untangled-app]]
    [om.next :as om :refer [defui]]
    [om.dom :as dom]
    [untangled.client.impl.network :as net]
    [untangled.client.mutations :as m]
    [untangled.client.data-fetch :as df]
    [untangled.client.logging :as log]))

(defrecord MockNetwork []
  net/UntangledNetwork
  (send [this edn done-callback error-callback]
    (js/console.log :asked-to-send edn)
    (js/setTimeout (fn []
                     (js/console.log :responding-to edn)
                     (if (= 'untangled.client.load-cards/add-thing (ffirst edn))
                       (let [tempid (-> edn first second :id)]
                         (done-callback {'untangled.client.load-cards/add-thing {:tempids {tempid 1010}}}))
                       (done-callback {[:thing/by-id 1010] {:id 1010 :label "B"}}))) 2000))
  (start [this complete-app] this))

(defui Thing
  static om/IQuery
  (query [this] [:id :label])
  static om/Ident
  (ident [this props] [:thing/by-id (:id props)])
  Object
  (render [this] (dom/div nil "THING")))

(defui Root
  Object
  (render [this]
    (dom/div nil "TODO")))

(m/defmutation add-thing
  "Read a thing"
  [{:keys [id label]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [:thing/by-id id] {:id id :label label}))
  (remote [env] true))

(defcard load-with-follow-on-read
  (untangled-app Root
    :networking (MockNetwork.)
    :started-callback (fn [{:keys [reconciler]}]
                        (let [id (om/tempid)]
                          (om/transact! reconciler `[(add-thing {:id ~id :label "A"})])
                          (df/load reconciler [:thing/by-id id] Thing))))
  {}
  {:inspect-data true})
