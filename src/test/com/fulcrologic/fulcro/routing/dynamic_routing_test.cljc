(ns com.fulcrologic.fulcro.routing.dynamic-routing-test
  (:require
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [clojure.test :refer [deftest]]
    [taoensso.timbre :as log]
    [fulcro-spec.core :refer [assertions]]))

(declare User Settings Root2 RootRouter2 SettingsPaneRouter Pane1 Pane2)

(deftest route-target-detection-test
  (assertions
    (dr/route-target? RootRouter2) => false
    (dr/route-target? Root2) => false
    (dr/route-target? Settings) => true))

(deftest route-lifecycle-detection-test
  (assertions
    (dr/route-lifecycle? RootRouter2) => false
    (dr/route-lifecycle? User) => true))

(deftest router-detection-test
  (assertions
    (dr/router? RootRouter2) => true
    (dr/router? User) => false))

(deftest matching-prefix-tests
  (assertions
    "Can match a parameterized path segment"
    (dr/matching-prefix ["user" :user-id] ["user" 1]) => ["user" "1"]
    "Can match a parameterized path segment with extra path elements"
    (dr/matching-prefix ["user" :user-id] ["user" 1 "blah"]) => ["user" "1"]
    "returns an nil if there is no match"
    (dr/matching-prefix ["user" :user-id] ["settings"]) => nil))

(deftest accepts-route-tests
  (assertions
    "correctly identifies when a router can consume the given path."
    (dr/accepts-route? RootRouter2 ["booga"]) => false
    (dr/accepts-route? RootRouter2 ["settings"]) => true
    (dr/accepts-route? RootRouter2 ["user"]) => false
    (dr/accepts-route? RootRouter2 ["user" "1"]) => true))

(deftest ast-node-for-route-test
  (let [ast          (eql/query->ast (comp/get-query Root2))
        settings-ast (eql/query->ast (comp/get-query Settings))]
    (assertions
      "returns nil for invalid routes"
      (dr/ast-node-for-route ast ["booga"]) => nil
      "Can find the router for a given path"
      (:component (dr/ast-node-for-route ast ["user" "1"])) => RootRouter2

      (:component (dr/ast-node-for-route settings-ast ["pane1"])) => SettingsPaneRouter
      (:component (dr/ast-node-for-route settings-ast ["pane2"])) => SettingsPaneRouter)))

(defsc Pane1 [this {:keys [:y] :as props}]
  {:query         (fn [] [:y {:sub-panel '...}])
   :ident         (fn [] [:COMPONENT/by-id :pane1])
   :initial-state (fn [_] {:y 1})
   :route-segment ["pane1"]
   :will-enter    (fn [_ _] (dr/route-immediate [:COMPONENT/by-id :pane1]))
   :will-leave    (fn [this props] true)})

(defsc Pane2 [this {:keys [:x] :as props}]
  {:query         [:x]
   :ident         (fn [] [:COMPONENT/by-id :pane2])
   :initial-state {:x 1}
   :route-segment ["pane2"]
   :will-enter    (fn [_ _] (dr/route-immediate [:COMPONENT/by-id :pane2]))
   :will-leave    (fn [this props] true)})

(dr/defrouter SettingsPaneRouter [_ _]
  {:router-targets [Pane1 Pane2]})

(defsc Settings [this {:keys [:x :panes] :as props}]
  {:query         [:x {:panes (comp/get-query SettingsPaneRouter)}]
   :ident         (fn [] [:COMPONENT/by-id :settings])
   :initial-state {:x     :param/x
                   :panes {}}
   :route-segment ["settings"]
   :will-enter    (fn [_ _] (dr/route-immediate [:COMPONENT/by-id :settings]))
   :will-leave    (fn [this props] true)})

(defsc User [this {:keys [user/id user/name] :as props}]
  {:query         [:user/id :user/name]
   :ident         [:user/id :user/id]
   :route-segment ["user" :user-id]
   :will-enter    (fn [app {:keys [user-id]}]
                    (let [id 1]
                      (df/load! app [:user/id id] User {:post-mutation        `dr/target-ready
                                                        :post-mutation-params {:target [:user/id id]}})
                      (dr/route-deferred [:user/id id]
                        (fn []))))
   :will-leave    (fn [this props] true)})

(dr/defrouter RootRouter2 [this {::keys [id current-route] :as props}]
  {:router-targets [User Settings]})

(defsc Root2 [this {:keys [router route] :as props}]
  {:query         [:route {:router (comp/get-query RootRouter2)}]
   :initial-state {:router {:x 2}
                   :route  ["settings"]}})


(defsc A [_ _]
  {:route-segment ["a" :a/param]})

(defsc B [_ _]
  {:route-segment ["b"]})

(defsc C [_ _]
  {:route-segment ["c" :c/param]})

(deftest into-path-test
  (assertions
    "Returns the route segment of a plain target"
    (dr/into-path ["x"] B) => ["x" "b"]
    "Returns the route segment of a parameterized target with the parameters changed to match the args"
    (dr/into-path ["y"] A "hello") => ["y" "a" "hello"]))

(deftest subpath-test
  (assertions
    "Returns the route segment of a plain target"
    (dr/subpath B) => ["b"]
    "Returns the route segment of a parameterized target with the parameters changed to match the args"
    (dr/subpath A "hello") => ["a" "hello"]))

(deftest path-to-test
  (assertions
    "Puts together multiple plain targets"
    (dr/path-to B B) => ["b" "b"]
    "Can replace parameters on a parameterized subpath"
    (dr/path-to A "hello" B) => ["a" "hello" "b"]
    (dr/path-to A "hello" B C "there") => ["a" "hello" "b" "c" "there"]
    "Can replace parameters via a map at the end"
    (dr/path-to A B C {:a/param "hello" :c/param "there"}) => ["a" "hello" "b" "c" "there"]))

