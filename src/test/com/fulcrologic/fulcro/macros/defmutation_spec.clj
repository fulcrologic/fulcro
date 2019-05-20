(ns com.fulcrologic.fulcro.macros.defmutation-spec
  (:require
    [com.fulcrologic.fulcro.macros.defmutation :as m]
    [fulcro-spec.core :refer [specification assertions component]]
    [clojure.test :refer :all]))

(declare =>)

(specification "defmutation Macro"
  (component "Defining a mutation into a different namespace"
    (let [actual (m/defmutation* {}
                   '(other/boo [params]
                      (action [env] (swap! state))))]
      (assertions
        "Just emits the defmethod"
        actual => `(defmethod com.fulcrologic.fulcro.mutations/mutate 'other/boo [~'fulcro-mutation-env-symbol]
                     (let [~'params (-> ~'fulcro-mutation-env-symbol :ast :params)]
                       {:result-action (fn [~'env] (com.fulcrologic.fulcro.mutations/default-result-action ~'env))
                        :action        (fn ~'action [~'env] (~'swap! ~'state) nil)})))))
  (component "Mutation remotes"
    (let [actual (m/defmutation* {}
                   '(boo [params]
                      (action [env] (swap! state))
                      (remote [env] true)
                      (rest [env] true)))
          the-do (nth actual 2)
          method (second the-do)]
      (assertions
        "Converts all sections to lambdas of a defmethod"
        method => `(defmethod com.fulcrologic.fulcro.mutations/mutate 'user/boo [~'fulcro-mutation-env-symbol]
                     (let [~'params (-> ~'fulcro-mutation-env-symbol :ast :params)]
                       {:action        (fn ~'action [~'env] (~'swap! ~'state) nil)
                        :result-action (fn [~'env] (com.fulcrologic.fulcro.mutations/default-result-action ~'env))
                        :remote        (fn ~'remote [~'env] true)
                        :rest          (fn ~'rest [~'env] true)})))))
  (component "Defining a mutation into the current namespace"
    (let [actual (m/defmutation* {}
                   '(boo [params]
                      (action [env] (swap! state))))
          sym    (second actual)
          the-do (nth actual 2)
          method (second the-do)
          decl   (last the-do)]
      (assertions
        "defs the plain symbol"
        (first actual) => `def
        sym => 'boo
        "Converts sections to lambdas of a defmethod"
        method => `(defmethod com.fulcrologic.fulcro.mutations/mutate 'user/boo [~'fulcro-mutation-env-symbol]
                     (let [~'params (-> ~'fulcro-mutation-env-symbol :ast :params)]
                       {:result-action (fn [~'env] (com.fulcrologic.fulcro.mutations/default-result-action ~'env))
                        :action        (fn ~'action [~'env] (~'swap! ~'state) nil)}))
        "the value of the def'd plain symbol is a Mutation declaration"
        decl => `(com.fulcrologic.fulcro.mutations/->Mutation user/boo)))))
