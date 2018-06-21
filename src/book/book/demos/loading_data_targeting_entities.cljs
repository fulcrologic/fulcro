(ns book.demos.loading-data-targeting-entities
  (:require
    [fulcro.client.mutations :as m]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.client.dom :as dom]
    [fulcro.server :as server]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.primitives :as prim]))

;; SERVER

(server/defquery-entity ::person-by-id
  (value [env id params]
    {:db/id id :person/name (str "Person " id)}))

;; CLIENT

(defsc Person [this {:keys [person/name]}]
  {:query [:db/id :person/name]
   :ident [::person-by-id :db/id]}
  (dom/div (str "Hi, I'm " name)))

(def ui-person (prim/factory Person {:keyfn :db/id}))

(defsc Pane [this {:keys [db/id pane/person] :as props}]
  {:query         [:db/id {:pane/person (prim/get-query Person)}]
   :initial-state (fn [{:keys [id]}] {:db/id id :pane/person nil})
   :ident         [:pane/by-id :db/id]}

  (dom/div
    (dom/h4 (str "Pane " id))
    (if person
      (ui-person person)
      (dom/div "No person loaded..."))))

(def ui-pane (prim/factory Pane {:keyfn :db/id}))

(defsc Panel [this {:keys [panel/left-pane panel/right-pane]}]
  {:query         [{:panel/left-pane (prim/get-query Pane)}
                   {:panel/right-pane (prim/get-query Pane)}]
   :initial-state (fn [params] {:panel/left-pane  (prim/get-initial-state Pane {:id :left})
                                :panel/right-pane (prim/get-initial-state Pane {:id :right})})
   :ident         (fn [] [:PANEL :only-one])}
  (dom/div
    (ui-pane left-pane)
    (ui-pane right-pane)))

(def ui-panel (prim/factory Panel {:keyfn :db/id}))

(defn load-random-person [component where]
  (let [load-target  (case where
                       (:left :right) [:pane/by-id where :pane/person]
                       :both (df/multiple-targets
                               [:pane/by-id :left :pane/person]
                               [:pane/by-id :right :pane/person]))

        person-ident [::person-by-id (rand-int 100)]]
    (df/load component person-ident Person {:target load-target :marker false})))

(defsc Root [this {:keys [root/panel] :as props}]
  {:query         [{:root/panel (prim/get-query Panel)}]
   :initial-state (fn [params] {:root/panel (prim/get-initial-state Panel {})})}
  (dom/div
    (ui-panel panel)
    (dom/button {:onClick #(load-random-person this :left)} "Load into Left")
    (dom/button {:onClick #(load-random-person this :right)} "Load into Right")
    (dom/button {:onClick #(load-random-person this :both)} "Load into Both")))


