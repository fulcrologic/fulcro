(ns fulcro-devguide.queries.query-demo
  (:require
    [fulcro.client.primitives :as prim :refer [defsc]]
    [cljs.pprint :refer [pprint]]
    [fulcro.client.dom :as dom]))

(defsc Person [this {:keys [person/name]}]
  {:query [:person/name]}
  (dom/li nil name))

(def person (prim/factory Person {:keyfn :db/id}))

(defsc PeopleWidget [this people]
  (dom/ul nil (map person people)))

(def people-list (prim/factory PeopleWidget))

(defsc Root [this {:keys [people]}]
  {:query [{:people (prim/get-query Person)}]}
  (dom/div nil (people-list people)))

(def root (prim/factory Root))

(def istate {
             :dashboard
             {
              :sidebar-open false
              :thing-widget {:display-mode :detailed
                             :things       [{:id 1 :name "Boo"} {:id 2 :name "Bah"}]}}})

; defui is about the same as defsc for defining stand-alone queries
(defsc Thing [this props]
  {:query [:ui/checked :id :name]
   :ident [:things/by-id :id]})

(defsc Things [this props]
  {:query [:display-mode {:things (prim/get-query Thing)}]
   ; ident needs to be constant...so we use lambda form
   :ident (fn [] [:widget :thing-list])})

(defsc Dashboard [this props]
  {:query [:sidebar-open {:thing-widget (prim/get-query Things)}]
   ; ident needs to be constant...so we use lambda form
   :ident (fn [] [:widget :dashboard])})

(defsc DRoot [this props]
  {:query [{:dashboard (prim/get-query Dashboard)}]})

(comment
  ; NOTE: Good example of converting an entire app state database tree into norm form
  istate
  (prim/get-query DRoot)
  (pprint (prim/tree->db DRoot istate true)))
