(ns untangled.i18n-spec
  (:require-macros [cljs.test :refer (is deftest testing are)]
                   [untangled-spec.core :refer (specification behavior provided assertions)]
                   )
  (:require [untangled.i18n :refer [current-locale] :refer-macros [tr trf trc trlambda]]
            yahoo.intl-messageformat-with-locales
            [cljs.test :refer [do-report]]
            [untangled.i18n.core :as i18n]))

(def translations
  {"|Hi"                                      "Ola"
   "Abbreviation for Monday|M"                "L"
   "|{n,plural,=0 {none} =1 {one} other {#}}" "{n,plural,=0 {nada} =1 {uno} other {#}}"})

(swap! i18n/*loaded-translations* (fn [x] (assoc x "es-MX" translations)))

(specification "Base translation -- tr"
  (reset! i18n/*current-locale* "en-US")
  (assertions "returns the string it is passed if there is no translation"
    (tr "Hello") => "Hello"
    "returns message key if current-locale is en-US"
    (tr "Hi") => "Hi")
  (reset! i18n/*current-locale* "es-MX")
  (assertions
    "returns message key if no translation map is found for the locale"
    (tr "Hello") => "Hello"
    "returns message key if translation is not found in the translation map"
    (tr "Hi") => "Ola"))

(specification "Base translation lambda -- trlambda"
  (reset! i18n/*current-locale* "en-US")
  (behavior "returns a function, which when called, does the translation."
    (is (= "Hello" ((trlambda "Hello"))))))

(specification "Message translations with context"
  (reset! i18n/*current-locale* "en-US")
  (assertions
    "Formats in en-US locale"
    (trc "Abbreviation for Monday" "M") => "M")
  (reset! i18n/*current-locale* "es-MX")
  (assertions
    "Formats in es-MX locale"
    (trc "Abbreviation for Monday" "M") => "L"))

(specification "Message format translation -- trf"
  (reset! i18n/*current-locale* "en-US")
  (behavior "returns the string it is passed if there is no translation"
    (is (= "Hello" (trf "Hello"))))
  (behavior "accepts a sequence of k/v pairs as arguments to the format"
    (is (= "A 1 B Sam" (trf "A {a} B {name}" :a 1 :name "Sam"))))
  (behavior "formats numbers - US"
    (is (= "18,349" (trf "{a, number}" :a 18349))))
  (assertions
    "formats dates - US"
    (trf "{a, date, long}" :a (js/Date. 1990 3 1 13 45 22 0)) => "April 1, 1990"
    (trf "{a, date, medium}" :a (js/Date. 1990 3 1 13 45 22 0)) => "Apr 1, 1990"
    (trf "{a, date, short}" :a (js/Date. 1990 3 1 13 45 22 0)) => "4/1/90")
  (behavior "formats plurals - US"
    (are [n msg] (= msg (trf "{n, plural, =0 {no apples} =1 {1 apple} other {# apples}}" :n n))
                 0 "no apples"
                 1 "1 apple"
                 2 "2 apples"
                 146 "146 apples"))
  (reset! i18n/*current-locale* "de-DE")
  (behavior "formats numbers - Germany"
    (is (= "18.349" (trf "{a, number}" :a 18349))))
  (reset! i18n/*current-locale* "es-MX")
  (assertions
    "formats dates - Mexico"
    (trf "{a, date, long}" :a (js/Date. 1990 3 1 13 45 22 0)) => "1 de abril de 1990"
    "Medium dates (browsers do different things here, so test is more generic)"
    (trf "{a, date, medium}" :a (js/Date. 1990 3 1 13 45 22 0)) =fn=> (fn [s] (re-matches #"^1 .*abr.*" s))
    (trf "{a, date, short}" :a (js/Date. 1990 3 1 13 45 22 0)) => "1/4/90")
  (behavior "formats plurals - Spanish"
    (are [n msg] (= msg (trf "{n,plural,=0 {none} =1 {one} other {#}}" :n n))
                 0 "nada"
                 1 "uno"
                 2 "2"
                 146 "146")))
