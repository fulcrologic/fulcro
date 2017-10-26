(ns fulcro-devguide.queries.query-demo
  (:require
    [fulcro.client.primitives :as om :refer-macros [defui]]
    [cljs.pprint :refer [pprint]]
    [fulcro.client.dom :as dom]))

(defui Person
  static om/IQuery
  (query [this] [:person/name])
  Object
  (render [this] (let [{:keys [person/name]} (om/props this)] (dom/li nil name))))

(def person (om/factory Person {:keyfn :db/id}))

(defui PeopleWidget
  Object
  (render [this] (let [people (om/props this)] (dom/ul nil (map person people)))))

(def people-list (om/factory PeopleWidget))

(defui Root
  static om/IQuery
  (query [this] [{:people (om/get-query Person)}])
  Object
  (render [this]
    (let [{:keys [people]} (om/props this)] (dom/div nil (people-list people)))))

(def root (om/factory Root))

(def istate {
             :dashboard
             {
              :sidebar-open false
              :thing-widget {:display-mode :detailed
                             :things       [{:id 1 :name "Boo"} {:id 2 :name "Bah"}]}}})

(defui Thing
  static om/IQuery
  (query [this] [:ui/checked :id :name])
  static om/Ident
  (ident [this props] [:things/by-id (:id props)]))

(defui Things
  static om/IQuery
  (query [this] [:display-mode {:things (om/get-query Thing)}])
  static om/Ident
  (ident [this props] [:widget :thing-list]))

(defui Dashboard
  static om/IQuery
  (query [this] [:sidebar-open {:thing-widget (om/get-query Things)}])
  static om/Ident
  (ident [this props] [:widget :dashboard]))

(defui DRoot
  static om/IQuery
  (query [this] [{:dashboard (om/get-query Dashboard)}]))

(comment
  ; NOTE TO SELF: Good example of converting an entire app state database tree into norm form
  istate
  (om/get-query DRoot)
  (pprint (om/tree->db DRoot istate true)))
