(ns fulcro.client.routing-spec
  (:require [fulcro-spec.core :refer [specification behavior assertions when-mocking component provided]]
            [fulcro.client.routing :as r :refer [defrouter]]
            #?(:cljs [fulcro.client.dom :as dom]
               :clj  [fulcro.client.dom-server :as dom])
            [fulcro.client.util :as util]
            [fulcro.client.primitives :as prim :refer [defui defsc]]
            [fulcro.client.mutations :as m]
            [fulcro.client :as fc]))

(defui Screen1
  prim/InitialAppState
  (initial-state [cls params] {:type :screen1})
  Object
  (render [this] (dom/div nil "TODO")))

(defrouter SampleRouter :router-1
  (ident [this props] [(:type props) :top])
  :screen1 Screen1)

(declare SampleRouter-Union)

(specification "Routers"
  (assertions
    "Have a top-level table namespaced to the fulcro routing library"
    (prim/get-ident SampleRouter {}) => [r/routers-table :router-1]
    "Use the user-supplied ident function for the union"
    (prim/get-ident SampleRouter-Union {:type :screen1}) => [:screen1 :top]))

(specification "current-route"
  (let [state-map {r/routers-table {:router-1 {::r/id :router-1 ::r/current-route [:A :top]}
                                    :router-2 {::r/id :router-2 ::r/current-route [:B :top]}}}]
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
                          {r/routers-table {:router-1 {::r/id :router-1 ::r/current-route [:unset :unset]}
                                            :router-2 {::r/id :router-2 ::r/current-route [:unset :unset]}}})
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
                          {r/routers-table {:router-1 {::r/id :router-1 ::r/current-route [:unset :unset]}}})
          new-state-map (r/update-routing-links state-map {:handler :boo :route-params {:some-id :target-id}})]
      (assertions
        "Switches the current routes with parameter substitutions"
        (r/current-route new-state-map :router-1) => [:screen1 :target-id]))))

#_(specification "load-dynamic-route"
  (behavior "retries on network failures (forever)" :manual-test)
  (behavior "Stops retrying if the user changes to another route (pending route changes or disappears)" :manual-test))

(specification "route-to mutation"
  (provided "There are no missing dynamically loaded routes: "
    (r/-get-missing-routes r s p) => []
    (r/-update-routing-queries s r m) => s

    (let [r         (r/make-route :boo [(r/router-instruction :router-1 [:screen1 :top])])
          r2        (r/make-route :foo [(r/router-instruction :router-2 [:screen2 :top])
                                        (r/router-instruction :router-1 [:screen1 :other])])
          tree      (r/routing-tree r r2)
          state-map (merge tree
                      {r/routers-table {:router-1 {::r/id :router-1 ::r/current-route [:initial :top]}
                                        :router-2 {::r/id :router-2 ::r/current-route [:initial :top]}}})
          state     (atom state-map)
          action    (:action (m/mutate {:state state} `r/route-to {:handler :boo}))]

      (action)

      (assertions
        "Switches the current routes according to the route instructions"
        (r/current-route @state :router-1) => [:screen1 :top])))
  (provided "There are one or more missing dynamically loaded routes: "
    (r/-get-missing-routes r s p) => [:main]
    (r/-load-routes env routes) => (do
                                    (assertions
                                      "Triggers loading on the missing screen"
                                      routes => [:main]))

    (let [r         (r/make-route :boo [(r/router-instruction :router-1 [:screen1 :top])])
          r2        (r/make-route :foo [(r/router-instruction :router-2 [:screen2 :top])
                                        (r/router-instruction :router-1 [:screen1 :other])])
          tree      (r/routing-tree r r2)
          state-map (merge tree
                      {r/routers-table {:router-1 {::r/id :router-1 ::r/current-route [:initial :top]}
                                        :router-2 {::r/id :router-2 ::r/current-route [:initial :top]}}})
          state     (atom state-map)
          action    (:action (m/mutate {:state state} `r/route-to {:handler :boo}))]

      (action)

      (assertions
        "Defers moving the current routes"
        (r/current-route @state :router-1) => [:initial :top]
        "Sets the pending route to the target match"
        (-> state deref ::r/pending-route) => {:handler :boo}))))

(specification "Completion of a dynamic route load (process-pending-route!)"
  (let [state-atom (atom {::r/pending-route {:handler :boo}})]
    (when-mocking
      (r/-update-routing-queries s r m) =1x=> (do
                                               (assertions
                                                 "Updates the queries on dynamic routers"
                                                 m => {:handler :boo})
                                               s)
      (r/update-routing-links s m) =1x=> (assertions
                                           "Updates the routing links"
                                           m => {:handler :boo})

      (#'r/-process-pending-route! {:state state-atom :reconciler :fake-reconciler})

      (assertions
        "Removes the pending route from the state"
        (-> state-atom deref ::r/pending-route) => nil))))


(specification "Route parameter substitution"
  (assertions
    "Is applied to both elements of the ident."
    (#'r/set-ident-route-params [:param/a :param/b] {:a :k :b 2}) => [:k 2]
    "Converts incoming string parameters that are integers into numbers"
    (#'r/set-ident-route-params [:param/a :param/b] {:a :person :b "2"}) => [:person 2]
    "Converts incoming string parameters that start with letters to keywords"
    (#'r/set-ident-route-params [:param/a :param/b] {:a "person" :b 2}) => [:person 2]
    "Leaves all other values alone"
    (#'r/set-ident-route-params [:param/a :param/b] {:a "9person" :b 2.6}) => ["9person" 2.6]
    (#'r/set-ident-route-params [:param/a :param/b] {:a :x :b :y}) => [:x :y]))

#?(:clj
   (specification "defsc-router"
     (component "defsc-router-union-element*"
       (let [code          (#'r/defsc-router-union-element* {} 'Union '[t p]
                             {:ident          `(~'fn [] [:a :b])
                              :router-id      :top-router
                              :router-targets {:a 'A :b 'B}
                              :default-route  'A}
                             [`(dom/div "Missing!")])
             options       (nth code 3)
             ident         (:ident options)
             initial-state (:initial-state options)
             query         (:query options)
             body          (nth code 4)
             [_ _ page-sym] (-> body second)]
         (assertions
           "has the proper ident function"
           ident => `(~'fn [] [:a :b])
           "has the proper initial state"
           initial-state => `(~'fn [~'params] (prim/get-initial-state ~'A ~'params))
           "has the correct union query"
           query => `(~'fn [] {:a (prim/get-query ~'A) :b (prim/get-query ~'B)})
           "renders the correct body"
           body => `(let [~'props (prim/props ~'this)
                          ~page-sym (first (prim/get-ident ~'this ~'props))]
                      (case ~page-sym
                        :a ((prim/factory ~'A) ~'props)
                        :b ((prim/factory ~'B) ~'props)
                        (let [~'t ~'this]
                          (dom/div "Missing!")))))))
     (component "defsc-router-router-element*"
       (let [code     (#'r/defsc-router-router-element* {} 'Router 'Router-Union '[t {:keys [a]} {:keys [y]} {:keys [boo]}]
                        {:ident          [:a :b]
                         :query          [:x]
                         :router-id      42
                         :router-targets {:x 'X}})
             sym      (second code)
             arglist  (nth code 2)
             options  (nth code 3)
             body     (nth code 4)
             let-args (second body)
             [computed-sym _ props-sym _ pwcomputed-sym _] let-args]
         (assertions
           "uses the provided symbol"
           sym => 'Router
           "uses the provided args list"
           arglist => '[t {:keys [a]} {:keys [y]} {:keys [boo]}]
           "Provides/Overrides the ident based on the supplied router id"
           (:ident options) => `(~'fn [] [:fulcro.client.routing.routers/by-id 42])
           "Provides/Overrides the query "
           (:query options) => `(~'fn [] [::r/id {::r/current-route (prim/get-query ~'Router-Union)}])
           "Removes the custom options"
           (contains? options :router-id) => false
           (contains? options :router-targets) => false
           "body properly extracts props and computed and passes through through to the union"
           body => `(let [~computed-sym (prim/get-computed ~'t)
                          ~props-sym (:fulcro.client.routing/current-route (prim/props ~'t))
                          ~pwcomputed-sym (prim/computed ~props-sym ~computed-sym)]
                      (~'ui-Router-Union ~pwcomputed-sym)))))))


