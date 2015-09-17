(ns untangled.i18n-spec
  (:require-macros [cljs.test :refer (is deftest testing)]
                   [smooth-spec.core :refer (specification behavior provided assertions)]
                   )
  (:require [untangled.history :as h]
            [untangled.i18n :refer-macros [tr trf trc]]
            [cljs.test :refer [do-report]])
  )

(specification "Base translation -- tr"
               (behavior "returns the string it is passed if there is no translation"
                         (is (= "Hello" (tr "Hello")))
                         )
               )

(specification "Message format translation -- trf"
               (behavior "returns the string it is passed if there is no translation"
                         (is (= "Hello" (trf "Hello")))
                         )
               (behavior "will accept a sequence of k/v pairs as arguments to the format"
                         (is (= "A 1 B Sam" (trf "A {a} B {name}" :a 1 :name "Sam"))))
               )
