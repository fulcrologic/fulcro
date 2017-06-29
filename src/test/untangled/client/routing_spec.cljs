(ns untangled.client.routing-spec
  (:require [untangled-spec.core :refer [specification behavior assertions when-mocking component]]
            [untangled.client.routing :as r :refer [defrouter]]
            [om.dom :as dom]
            [om.next :as om :refer [defui]]
            [untangled.client.mutations :as m]
            [untangled.client.core :as uc]))

(defui Screen1
  uc/InitialAppState
  (initial-state [cls params] {:type :screen1})
  Object
  (render [this] (dom/div nil "TODO")))

(defrouter SampleRouter :router-1
  (ident [this props] [(:type props) :top])
  :screen1 Screen1)

(declare SampleRouter-Union)

(specification "Routers"
  (assertions
    "Have a top-level table namespaced to the untangled routing library"
    (om/ident SampleRouter {}) => [r/routers-table :router-1]
    "Use the user-supplied ident function for the union"
    (om/ident SampleRouter-Union {:type :screen1}) => [:screen1 :top]))

(specification "current-route"
  (let [state-map {r/routers-table {:router-1 {:id :router-1 :current-route [:A :top]}
                                    :router-2 {:id :router-2 :current-route [:B :top]}}}]
    (assertions
      "Can read the current route from a router"
      (r/current-route state-map :router-1) => [:A :top]
      (r/current-route state-map :router-2) => [:B :top])))

(specification "update-routing-links"
  (component "on non-parameterized routes"
    (let [r             (r/make-route :boo [(r/router-instruction :router-1 [:screen1 :top])])
          r2            (r/make-route :foo [(r/router-instruction :router-2 [:screen2 :top])
                                            (r/router-instruction :router-1 [:screen1 :other])])
          tree          (r/routing-tree r r2)
          state-map     (merge
                          tree
                          {r/routers-table {:router-1 {:id :router-1 :current-route [:unset :unset]}
                                            :router-2 {:id :router-2 :current-route [:unset :unset]}}})
          new-state-map (r/update-routing-links state-map {:handler :foo})]
      (assertions
        "Switches the current routes according to the route instructions"
        (r/current-route new-state-map :router-1) => [:screen1 :other]
        (r/current-route new-state-map :router-2) => [:screen2 :top])))
  (component "on parameterized routes"
    (let [r             (r/make-route :boo [(r/router-instruction :router-1 [:screen1 :param/some-id])])
          tree          (r/routing-tree r)
          state-map     (merge
                          tree
                          {r/routers-table {:router-1 {:id :router-1 :current-route [:unset :unset]}}})
          new-state-map (r/update-routing-links state-map {:handler :boo :route-params {:some-id :target-id}})]
      (assertions
        "Switches the current routes with parameter substitutions"
        (r/current-route new-state-map :router-1) => [:screen1 :target-id]))))

(specification "route-to mutation"
  (let [r         (r/make-route :boo [(r/router-instruction :router-1 [:screen1 :top])])
        r2        (r/make-route :foo [(r/router-instruction :router-2 [:screen2 :top])
                                      (r/router-instruction :router-1 [:screen1 :other])])
        tree      (r/routing-tree r r2)
        state-map (merge tree
                    {r/routers-table {:router-1 {:id :router-1 :current-route [:initial :top]}
                                      :router-2 {:id :router-2 :current-route [:initial :top]}}})
        state     (atom state-map)
        action    (:action (m/mutate {:state state} `r/route-to {:handler :boo}))]

    (action)

    (assertions
      "Switches the current routes according to the route instructions"
      (r/current-route @state :router-1) => [:screen1 :top])))


