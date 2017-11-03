(ns recipes.load-samples-client
  (:require
    [fulcro.client.core :as fc :refer [InitialAppState initial-state]]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.mutations :as m]
    [fulcro.client.primitives :as prim]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defui]]))

(defui ^:once Person
  static prim/IQuery
  (query [this] [:db/id :person/name :person/age-ms :ui/fetch-state])
  static prim/Ident
  (ident [this props] [:load-samples.person/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [db/id person/name person/age-ms] :as props} (prim/props this)]
      (dom/li nil
        (str name " (last queried at " age-ms ")")
        (dom/button #js {:onClick (fn []
                                    ; Load relative to an ident (of this component).
                                    ; This will refresh the entity in the db. The helper function
                                    ; (df/refresh! this) is identical to this, but shorter to write.
                                    (df/load this (prim/ident this props) Person))} "Update")))))

(def ui-person (prim/factory Person {:keyfn :db/id}))

(defui ^:once People
  static fc/InitialAppState
  (initial-state [c {:keys [kind]}] {:people/kind kind})
  static prim/IQuery
  (query [this] [:people/kind {:people (prim/get-query Person)}])
  static prim/Ident
  (ident [this props] [:lists/by-type (:people/kind props)])
  Object
  (render [this]
    (let [{:keys [people]} (prim/props this)]
      (dom/ul nil
        ; we're loading a whole list. To sense/show a loading marker the :ui/fetch-state has to be queried in Person.
        ; Note the whole list is what we're loading, so the render lambda is a map over all of the incoming people.
        (df/lazily-loaded #(map ui-person %) people)))))

(def ui-people (prim/factory People {:keyfn :people/kind}))

(defui ^:once Root
  static fc/InitialAppState
  (initial-state [c {:keys [kind]}] {:friends (fc/get-initial-state People {:kind :friends})
                                     :enemies (fc/get-initial-state People {:kind :enemies})})
  static prim/IQuery
  (query [this] [:ui/react-key
                 {:enemies (prim/get-query People)}
                 {:friends (prim/get-query People)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key friends enemies]} (prim/props this)]
      (dom/div #js {:key react-key}
        (dom/h4 nil "Friends")
        (ui-people friends)
        (dom/h4 nil "Enemies")
        (ui-people enemies)))))

(defonce app (atom (fc/new-fulcro-client
                     :started-callback (fn [app]
                                         ; Make sure you're running the app from the real server port (not fighweel).
                                         ; This is a sample of loading a list of people into a given target, including
                                         ; use of params. The generated network query will result in params
                                         ; appearing in the server-side query, and :people will be the dispatch
                                         ; key. The subquery will also be available (from Person)
                                         (df/load app :load-samples/people Person {:target [:lists/by-type :enemies :people]
                                                                                   :params {:kind :enemy}})
                                         (df/load app :load-samples/people Person {:target [:lists/by-type :friends :people]
                                                                                   :params {:kind :friend}})))))
