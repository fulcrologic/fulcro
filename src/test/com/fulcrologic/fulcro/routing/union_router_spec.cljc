(ns com.fulcrologic.fulcro.routing.union-router-spec
  (:require [clojure.test :refer :all]
          [fulcro-spec.core :refer [specification assertions component]]
          [com.fulcrologic.fulcro.routing.union-router :as fr]
          [com.fulcrologic.fulcro.components :as comp]))

(comp/defsc SimpleTarget [_ {:PAGE/keys [ident id]}]
  {:query         [:PAGE/id
                   :PAGE/ident]
   :ident         (fn [] [ident id])
   :initial-state (fn [_]
                    {:PAGE/id    :PAGE/simple-target
                     :PAGE/ident :PAGE/simple-target})})

(fr/defsc-router SimpleRouter [_ {:PAGE/keys [ident id]}]
  {:default-route  SimpleTarget
   :ident          (fn [] [ident id])
   :router-targets {:PAGE/simple-target SimpleTarget}
   :router-id      :PAGE/root-router})

(specification "defsc-router Macro"
               (component "Basic feature access"
                          (assertions
                            "Just returns it's query"
                            (comp/get-query SimpleRouter)
                            => [::fr/id
                                {::fr/current-route {:PAGE/simple-target [:PAGE/id :PAGE/ident]}}])))
