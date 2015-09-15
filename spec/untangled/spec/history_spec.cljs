(ns untangled.spec.history-spec
  (:require-macros [cljs.test :refer (is deftest testing)]
                   [smooth-test.core :refer (specification behavior provided assertions)]
                   )
  (:require [untangled.history :as h]
            [cljs.test :refer [do-report]])
  )

(specification "Points in time"
  (behavior "default to undoable"
            (assertions
              (-> (h/new-point-in-time "A") :undoable) => true
              ))
  (behavior "default to not collapsable"
            (assertions
              (:can-collapse? (h/new-point-in-time "A")) => false
              ))
  (behavior "can have a reason"
            (assertions
              (-> (h/new-point-in-time "A") (h/set-reason "because") :reason) => "because"
                        )))

(specification "Collapsing history"
  (behavior "removes adjacent collapseable entries"
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
  (behavior "trims history to the configured limit"
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

(specification "Recording history"
  (behavior "Holds the most recent entry at the front of the entries"
    (is (= (h/new-point-in-time "B" true false)
           (-> (h/empty-history 2) (h/record (h/new-point-in-time "A" true false)) (h/record (h/new-point-in-time "B" true false))
               :entries first)
           )))
  (behavior "Records at least as many events as the history is designed to hold"
    (is (=
          (-> (h/empty-history 2) (h/record (h/new-point-in-time "A" true false)) (h/record (h/new-point-in-time "B" true false)))
          (h/->History (list
                         (h/new-point-in-time "B" true false)
                         (h/new-point-in-time "A" true false)
                         ) 2)
          )))
  (behavior "Collapses adjacent collapsable entries as they are added"
    (is (=
          (-> (h/empty-history 2) (h/record (h/new-point-in-time "A" true true)) (h/record (h/new-point-in-time "B" true true)))
          (h/->History (list
                         (h/new-point-in-time "B" true true)
                         ) 2)
          )))
  (behavior "removes old entries as new ones are added"
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
