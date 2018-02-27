(ns fulcro.alpha.i18n-spec
  (:require [fulcro-spec.core :refer [specification behavior provided when-mocking assertions]]
            [fulcro.alpha.i18n :as i18n :refer [tr trf trc]]
            [fulcro.logging :as log]))

(specification "Locale loading from PO files." :focused
  (when-mocking
    (log/-log l lvl & args) => (assertions
                                 "Logs an error when no locale is found"
                                 lvl => :error)

    (let [xlation         (i18n/load-locale "fulcro/alpha" :es)
          missing-xlation (i18n/load-locale "boo" :xx)]

      (assertions
        "Returns nil if no locale is found"
        missing-xlation => nil
        "Loaded translation exists"
        xlation => {::i18n/locale       :es
                    ::i18n/translations {["" "It is {n,date}"]       "Es {n, date}"
                                         ["" "Hello, {name}"]        "Hola {name}"
                                         ["Gender abbreviation" "M"] "M"
                                         ["" "Hello"]                "Hola"}}))))
