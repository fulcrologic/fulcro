(ns fulcro.client.impl.parser-spec
  (:require
    [fulcro.client.core :as fc]
    [fulcro.client.primitives :as prim :refer [defui defsc]]
    [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
    [fulcro.client.dom :as dom]
    [fulcro.i18n :as i18n]
    [fulcro.client.impl.application :as app]
    [fulcro.client.mutations :as m]))

(m/defmutation sample-mutation-1 [params]
  (action [{:keys [state]}]
    (swap! state assoc :update [1]))
  (refresh [env] [:x :y]))

(m/defmutation sample-mutation-2 [params]
  (action [{:keys [state]}]
    (swap! state update :update conj 2))
  (refresh [env] [:a :b]))

(def parser (prim/parser {:read (partial app/read-local (constantly nil)) :mutate m/mutate}))

(specification "Parser reads"
  (let [state-db   {:top   [:table 1]
                    :table {1 {:id 1 :value :v}}}
        state-atom (atom state-db)
        env        {:state state-atom}]
    (assertions
      "can produce query results"
      (parser env [{:top [:value]}]) => {:top {:value :v}})))

(specification "Mutations"
  (let [state  (atom {})
        env    {:state state}
        result (parser env `[(sample-mutation-1 {}) (sample-mutation-2 {})])]
    (assertions
      "Runs the actions of the mutations, in order"
      @state => {:update [1 2]}
      "Includes a refresh set on the metadata of the result."
      (meta result) => {::prim/refresh #{:x :y :a :b}})))
