(ns cards.targeting-an-entity-load
  (:require
    #?@(:cljs [[devcards.core :as dc :include-macros true]
               [fulcro.client.cards :refer [defcard-fulcro]]])
    [fulcro.client.mutations :as m]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.client.dom :as dom]
    [fulcro.server :as server]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
    [fulcro.client :as fc]
    [fulcro.client.primitives :as prim]))

(server/defquery-entity :person/by-id
  (value [env id params]
    {:db/id id :person/name (str "Person " id)}))

(defsc Person [this {:keys [person/name]}]
  {:query [:db/id :person/name]
   :ident [:person/by-id :db/id]}
  (dom/div nil (str "Hi, I'm " name)))

(def ui-person (prim/factory Person {:keyfn :db/id}))

; TODO: Write tests around refresh of mult targets
; TODO: Basis time fixes with respect to targeted refresh

(defsc Pane [this {:keys [db/id pane/person] :as props}]
  {:query         [:db/id {:pane/person (prim/get-query Person)}]
   :initial-state (fn [{:keys [id]}] {:db/id id :pane/person nil})
   :ident         [:pane/by-id :db/id]}
  #?(:cljs (js/console.log :pane id props))
  (dom/div nil
    (dom/h4 nil (str "Pane " id))
    (if person
      (ui-person person)
      (dom/div nil "No person loaded..."))))

(def ui-pane (prim/factory Pane {:keyfn :db/id}))

(defsc Panel [this {:keys [panel/left-pane panel/right-pane]}]
  {:query         [{:panel/left-pane (prim/get-query Pane)}
                   {:panel/right-pane (prim/get-query Pane)}]
   :initial-state (fn [params] {:panel/left-pane  (prim/get-initial-state Pane {:id :left})
                                :panel/right-pane (prim/get-initial-state Pane {:id :right})})
   :ident         (fn [] [:PANEL :only-one])}
  (dom/div nil
    (ui-pane left-pane)
    (ui-pane right-pane)))

(def ui-panel (prim/factory Panel {:keyfn :db/id}))

(defn load-random-person [component where]
  (let [load-target  (case where
                       (:left :right) [:pane/by-id where :pane/person]
                       :both (df/multiple-targets
                               [:pane/by-id :left :pane/person]
                               [:pane/by-id :right :pane/person]))
        person-ident [:person/by-id (rand-int 100)]]
    (df/load component person-ident Person {:target load-target})))

(defsc Root [this {:keys [root/panel ui/react-key] :as props}]
  {:query         [:ui/react-key {:root/panel (prim/get-query Panel)}]
   :initial-state (fn [params] {:root/panel (prim/get-initial-state Panel {})})}
  #?(:cljs (js/console.log :root props))
  (dom/div #js {:key react-key}
    (ui-panel panel)
    (dom/button #js {:onClick #(load-random-person this :left)} "Load into Left")
    (dom/button #js {:onClick #(load-random-person this :right)} "Load into Right")
    (dom/button #js {:onClick #(load-random-person this :both)} "Load into Both")))

#?(:cljs
   (dc/defcard-doc
     "# Targeting an Entity Load

     The data fetch system supports loading specific normalized data directly into tables. The requirements are simple:

     - There must be a component that has a query and ident (for normalization)
     - Your server must be able to respond to a query for that kind of entity

     In the example code below, you'll see we've set up two panes in a panel, and each pane can render a person.

     Initially, there are no people loaded (none in initial state). The load buttons in the root component
     let you see entity loads targeted at one or both of them.

     The server is rather simple. It just makes up a person for any given ID:

     ```
     (server/defquery-entity :person/by-id
       (value [env id params]
         {:db/id id :person/name (str \"Person\" id)}))
     ```

     And the UI is coded as follows:
     "
     (dc/mkdn-pprint-source Person)
     (dc/mkdn-pprint-source ui-person)
     (dc/mkdn-pprint-source Pane)
     (dc/mkdn-pprint-source ui-pane)
     (dc/mkdn-pprint-source Panel)
     (dc/mkdn-pprint-source ui-panel)
     (dc/mkdn-pprint-source Root)))

#?(:cljs
   (defcard-fulcro targeted-entity
     "# Live Demo

     This card is running the code above.
     "
     Root
     {}
     {:inspect-data true}))
