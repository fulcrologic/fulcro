(ns com.fulcrologic.fulcro.application-spec
  (:require
    [fulcro-spec.core :refer [specification provided! when-mocking! assertions behavior when-mocking component]]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.specs :refer [atom-of]]
    [com.fulcrologic.fulcro.algorithms.misc :as util]
    [com.fulcrologic.fulcro.application :as app :refer [fulcro-app]]
    [clojure.test :refer [is are deftest]]))

(deftest application-constructor
  (let [app (app/fulcro-app)]
    (assertions
      (s/valid? ::app/app app) => true)))
