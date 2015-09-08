(ns untangled.history-spec
  (:require-macros [cljs.test :refer (is deftest run-tests testing)])
  (:require [untangled.history :as h]
            [cljs.test :as t])
  )

(deftest point-in-time-spec
  (testing "default to undoable" (is (= true (:undoable (h/new-point-in-time "A")))))
  (testing "default to not collapsable" (is (= false (:can-collapse? (h/new-point-in-time "A")))))
  (testing "can have a reason" (is (= "because" (-> (h/new-point-in-time "A") (h/set-reason "because") :reason))))
  )

(deftest collapsing-history
  (testing "removes adjacent collapseable entries"
    (is (= (h/collapse-history (h/->History (list
                                              (h/new-point-in-time "E" true true)
                                              (h/new-point-in-time "D" true true)
                                              (h/new-point-in-time "C" true true)
                                              (h/new-point-in-time "B" true false)
                                              (h/new-point-in-time "A" true true)
                                              ) 10))
           (h/->History (list
                          (h/new-point-in-time "E" true true)
                          (h/new-point-in-time "B" true false)
                          (h/new-point-in-time "A" true true)
                          ) 10)
           )))
  (testing "trims history to the configured limit"
    (is (= (h/collapse-history (h/->History (list
                                              (h/new-point-in-time "E" true false)
                                              (h/new-point-in-time "D" true false)
                                              (h/new-point-in-time "C" true false)
                                              (h/new-point-in-time "B" true false)
                                              (h/new-point-in-time "A" true false)
                                              ) 3))
           (h/->History (list
                          (h/new-point-in-time "E" true false)
                          (h/new-point-in-time "D" true false)
                          (h/new-point-in-time "C" true false)
                          ) 3)
           )))
  )

(deftest recording-history-spec
  (testing "Holds the most recent entry at the front of the entries"
    (is (= (h/new-point-in-time "B" true false)
           (-> (h/empty-history 2) (h/record (h/new-point-in-time "A" true false)) (h/record (h/new-point-in-time "B" true false))
               :entries first)
           )))
  (testing "Records at least as many events as the history is designed to hold"
    (is (=
          (-> (h/empty-history 2) (h/record (h/new-point-in-time "A" true false)) (h/record (h/new-point-in-time "B" true false)))
          (h/->History (list
                         (h/new-point-in-time "B" true false)
                         (h/new-point-in-time "A" true false)
                         ) 2)
          )))
  (testing "Collapses adjacent collapsable entries as they are added"
    (is (=
          (-> (h/empty-history 2) (h/record (h/new-point-in-time "A" true true)) (h/record (h/new-point-in-time "B" true true)))
          (h/->History (list
                         (h/new-point-in-time "B" true true)
                         ) 2)
          )))
  (testing "removes old entries as new ones are added"
    (is (=
          (-> (h/empty-history 2)
              (h/record (h/new-point-in-time "A" true false))
              (h/record (h/new-point-in-time "B" true false))
              (h/record (h/new-point-in-time "C" true false)))
          (h/->History (list
                         (h/new-point-in-time "C" true false)
                         (h/new-point-in-time "B" true false)
                         ) 2)
          )))
  )
