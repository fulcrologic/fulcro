(ns untangled.i18n-spec
  (:require-macros [cljs.test :refer (is deftest testing are)]
                   [smooth-spec.core :refer (specification behavior provided assertions)]
                   )
  (:require [untangled.history :as h]
            [untangled.i18n :refer [current-locale]]
            [untangled.i18n :refer-macros [tr trf trc trlambda]]
            [cljs.test :refer [do-report]])
  )




(specification "Base translation -- tr"
               (behavior "returns the string it is passed if there is no translation"
                         (is (= "Hello" (tr "Hello")))
                         )
               (behavior "returns message key if current-locale is en-US")
               (behavior "returns message key if no translation map is found for the locale")
               (behavior "returns message key if translation is not found in the translation map")
               )

(specification "Base translation lambda -- trlambda"
               (behavior "returns a function, which when called, does the translation."
                         (is (= "Hello" ((trlambda "Hello"))))
                         )
               )

(specification "Message format translation -- trf"
               (behavior "returns the string it is passed if there is no translation"
                         (is (= "Hello" (trf "Hello")))
                         )
               (behavior "accepts a sequence of k/v pairs as arguments to the format"
                         (is (= "A 1 B Sam" (trf "A {a} B {name}" :a 1 :name "Sam"))))
               (behavior "formats numbers"
                         (is (= "18,349" (trf "{a, number}" :a 18349))))
               (behavior "formats dates"
                         (is (= "April 1, 1990" (trf "{a, date, long}" :a (js/Date. 1990 3 1 13 45 22 0))))
                         (is (= "Apr 1, 1990" (trf "{a, date, medium}" :a (js/Date. 1990 3 1 13 45 22 0))))
                         (is (= "4/1/90" (trf "{a, date, short}" :a (js/Date. 1990 3 1 13 45 22 0))))
                         )
               (behavior "formats plurals"
                         (are [n msg] (= msg (trf "{n, plural, =0 {no apples} =1 {1 apple} other {# apples}}" :n n))
                                      0 "no apples"
                                      1 "1 apple"
                                      2 "2 apples"
                                      146 "146 apples"
                                      )
                         )
               )
