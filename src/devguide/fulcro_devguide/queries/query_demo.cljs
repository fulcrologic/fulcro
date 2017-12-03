(ns fulcro-devguide.queries.query-demo
  (:require
    [fulcro.client.primitives :as prim :refer-macros [defui]]
    [cljs.pprint :refer [pprint]]
    [fulcro.client.dom :as dom]))

(defui Person
  static prim/IQuery
  (query [this] [:person/name])
  Object
  (render [this] (let [{:keys [person/name]} (prim/props this)] (dom/li nil name))))

(def person (prim/factory Person {:keyfn :db/id}))

(defui PeopleWidget
  Object
  (render [this] (let [people (prim/props this)] (dom/ul nil (map person people)))))

(def people-list (prim/factory PeopleWidget))

(defui Root
  static prim/IQuery
  (query [this] [{:people (prim/get-query Person)}])
  Object
  (render [this]
    (let [{:keys [people]} (prim/props this)] (dom/div nil (people-list people)))))

(def root (prim/factory Root))

(def istate {
             :dashboard
             {
              :sidebar-open false
              :thing-widget {:display-mode :detailed
                             :things       [{:id 1 :name "Boo"} {:id 2 :name "Bah"}]}}})

(defui Thing
  static prim/IQuery
  (query [this] [:ui/checked :id :name])
  static prim/Ident
  (ident [this props] [:things/by-id (:id props)]))

(defui Things
  static prim/IQuery
  (query [this] [:display-mode {:things (prim/get-query Thing)}])
  static prim/Ident
  (ident [this props] [:widget :thing-list]))

(defui Dashboard
  static prim/IQuery
  (query [this] [:sidebar-open {:thing-widget (prim/get-query Things)}])
  static prim/Ident
  (ident [this props] [:widget :dashboard]))

(defui DRoot
  static prim/IQuery
  (query [this] [{:dashboard (prim/get-query Dashboard)}]))

(comment
  ; NOTE TO SELF: Good example of converting an entire app state database tree into norm form
  istate
  (prim/get-query DRoot)
  (pprint (prim/tree->db DRoot istate true)))
