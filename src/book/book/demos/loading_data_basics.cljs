(ns book.demos.loading-data-basics
  (:require
    [fulcro.client :as fc]
    [fulcro.client.data-fetch :as df]
    [book.demos.util :refer [now]]
    [fulcro.client.mutations :as m]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc InitialAppState initial-state]]
    [fulcro.client.data-fetch :as df]
    [fulcro.server :as server]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def all-users [{:db/id 1 :person/name "A" :kind :friend}
                {:db/id 2 :person/name "B" :kind :friend}
                {:db/id 3 :person/name "C" :kind :enemy}
                {:db/id 4 :person/name "D" :kind :friend}])

(server/defquery-entity :load-samples.person/by-id
  (value [{:keys [] :as env} id p]
    (let [person (first (filter #(= id (:db/id %)) all-users))]
      (assoc person :person/age-ms (now)))))

(server/defquery-root :load-samples/people
  (value [env {:keys [kind]}]
    (let [result (->> all-users
                   (filter (fn [p] (= kind (:kind p))))
                   (mapv (fn [p] (-> p
                                   (select-keys [:db/id :person/name])
                                   (assoc :person/age-ms (now))))))]
      result)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLIENT:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defsc Person [this {:keys [db/id person/name person/age-ms] :as props}]
  {:query [:db/id :person/name :person/age-ms :ui/fetch-state]
   :ident (fn [] [:load-samples.person/by-id id])}
  (dom/li
    (str name " (last queried at " age-ms ")")
    (dom/button {:onClick (fn []
                            ; Load relative to an ident (of this component).
                            ; This will refresh the entity in the db. The helper function
                            ; (df/refresh! this) is identical to this, but shorter to write.
                            (df/load this (prim/ident this props) Person))} "Update")))

(def ui-person (prim/factory Person {:keyfn :db/id}))

(defsc People [this {:keys [people]}]
  {:initial-state (fn [{:keys [kind]}] {:people/kind kind})
   :query         [:people/kind {:people (prim/get-query Person)}]
   :ident         [:lists/by-type :people/kind]}
  (dom/ul
    ; we're loading a whole list. To sense/show a loading marker the :ui/fetch-state has to be queried in Person.
    ; Note the whole list is what we're loading, so the render lambda is a map over all of the incoming people.
    (df/lazily-loaded #(map ui-person %) people)))

(def ui-people (prim/factory People {:keyfn :people/kind}))

(defsc Root [this {:keys [friends enemies]}]
  {:initial-state (fn [{:keys [kind]}] {:friends (prim/get-initial-state People {:kind :friends})
                                        :enemies (prim/get-initial-state People {:kind :enemies})})
   :query         [{:enemies (prim/get-query People)} {:friends (prim/get-query People)}]}
  (dom/div
    (dom/h4 "Friends")
    (ui-people friends)
    (dom/h4 "Enemies")
    (ui-people enemies)))

(defn initialize
  "To be used in :started-callback to pre-load things."
  [app]
  ; This is a sample of loading a list of people into a given target, including
  ; use of params. The generated network query will result in params
  ; appearing in the server-side query, and :people will be the dispatch
  ; key. The subquery will also be available (from Person). See the server code above.
  (df/load app :load-samples/people Person {:target [:lists/by-type :enemies :people]
                                            :params {:kind :enemy}})
  (df/load app :load-samples/people Person {:target [:lists/by-type :friends :people]
                                            :params {:kind :friend}}))

