(ns ^:figwheel-always calendar-demo.calendar-spec
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [calendar-demo.calendar :refer [initial-calendar displayed-date set-date next-month prior-month]]
    )
  )

(deftest initial-calendar-test
         (let [cal (initial-calendar (js/Date. 1990 0 11 1 0 0 0))]
              (is (= 1990 (:year cal)))
              (is (= 3 (:month cal)))
              (is (= 11 (:day cal)))
              )
         )

