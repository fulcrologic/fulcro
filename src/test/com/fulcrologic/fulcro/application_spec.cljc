(ns com.fulcrologic.fulcro.application-spec
  (:require
    [fulcro-spec.core :refer [specification provided! when-mocking! assertions behavior when-mocking component]]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.specs]
    [com.fulcrologic.fulcro.algorithms.misc :as util]
    [com.fulcrologic.fulcro.application :as app :refer [fulcro-app]]
    [clojure.test :refer [is are deftest]]))

(deftest application-constructor
  (let [app (app/fulcro-app)]
    (assertions
      (s/valid? ::app/app app) => true)))

(deftest props-only-query-test
  (assertions
    "Can extract the correct props-only query from an arbitrary query."
    (app/props-only-query '[:a {:b [:x]} (f) (:c {:y 1}) ({:j [:y]} {:no 2})])
    => [:a :b :c :j]))
