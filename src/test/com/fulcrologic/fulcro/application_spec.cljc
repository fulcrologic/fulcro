(ns com.fulcrologic.fulcro.application-spec
  (:require
    [fulcro-spec.core :refer [specification provided! when-mocking! assertions behavior when-mocking component]]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.specs]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as util]
    [com.fulcrologic.fulcro.application :as app :refer [fulcro-app]]
    [clojure.test :refer [is are deftest]]))

(deftest application-constructor
  (let [app (app/fulcro-app)]
    (assertions
      (s/valid? ::app/app app) => true)))

(specification "Static extensible configuration"
  (let [app (app/fulcro-app {:external-config {::x 1}})]
    (assertions
      "Allows arbitrary k-v pairs to be added to the static config"
      (comp/external-config app ::x) => 1)))
