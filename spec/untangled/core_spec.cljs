(ns untangled.core-spec
  (:require-macros [cljs.test :refer (is deftest run-tests testing)]
                   [smooth-spec.core :refer (specification behavior provided assertions)])
  (:require [cljs.test :refer [do-report]]
            [untangled.core :refer [translate-item-path]]
            )
  )

(specification
  "The translate-item-path (function)"
  (let [app-state (atom {:top
                               {:summary    "",
                                :namespaces [{:name "untangled.test.dom-spec", :test-items [{:id "xyz"}]}],
                                :pass       3,
                                :fail       2,
                                :error      0},
                         :time #inst "2015-09-09T22:31:48.759-00:00"})]
    (behavior
      "translates item path to a path that can be used with get-in"
      (is (= [:top :namespaces 0] (translate-item-path app-state [:namespaces :name "untangled.test.dom-spec" 0])))
      (is (= [:top :namespaces 0 :test-items 0]
             (translate-item-path app-state [:namespaces :name "untangled.test.dom-spec" 0 :test-items :id "xyz" 0])))))
  )

