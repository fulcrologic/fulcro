(ns fulcro.client.mutations-spec
  (:require
    [fulcro-spec.core :refer [assertions specification behavior]]
    [fulcro.client.mutations :as m]
    [clojure.test :refer :all]))

(specification "defmutation Macro"
  (assertions
    "Wraps action sections in lambdas"
    (m/defmutation* {}
      '(boo [params]
         (action [env] (swap! state))))
    => '(clojure.core/defmethod fulcro.client.mutations/mutate (quote user/boo)
          [fulcro-incoming-env _ params]
          (clojure.core/merge
            (clojure.core/let [env fulcro-incoming-env]
              {:action (clojure.core/fn [] (swap! state))})))

    (m/defmutation* {}
      '(boo [params]
         (custom-action [env] (swap! state))))
    => '(clojure.core/defmethod fulcro.client.mutations/mutate (quote user/boo)
          [fulcro-incoming-env _ params]
          (clojure.core/merge
            (clojure.core/let [env fulcro-incoming-env]
              {:custom-action (clojure.core/fn [] (swap! state))}))))
  (assertions
    "groups remote statements into a `do`"
    (m/defmutation* {}
      '(boo [params]
         (remote [env] true)))
    => '(clojure.core/defmethod fulcro.client.mutations/mutate (quote user/boo)
          [fulcro-incoming-env _ params]
          (clojure.core/merge
            (clojure.core/let [env fulcro-incoming-env]
              {:remote (do true)}))))

  (assertions
    "Allows combinations of remotes and actions"
    (m/defmutation* {}
      '(boo [params]
         (remote [env] true)
         (action [env] :noop)
         (custom-action [env] :other)))
    => '(clojure.core/defmethod fulcro.client.mutations/mutate (quote user/boo)
          [fulcro-incoming-env _ params]
          (clojure.core/merge
            (clojure.core/let [env fulcro-incoming-env]
              {:action (clojure.core/fn [] :noop)})
            (clojure.core/let [env fulcro-incoming-env]
              {:custom-action (clojure.core/fn [] :other)})
            (clojure.core/let [env fulcro-incoming-env]
              {:remote (do true)})))))
