(ns fulcro.i18n-spec
  (:require [fulcro-spec.core :refer [specification behavior provided assertions]]
            [clojure.string :as str]
            [fulcro.i18n :as i18n :refer [*current-locale* tr trf trc trlambda]]))

(def translations
  {"|Hi"                                      "Ola"
   "Abbreviation for Monday|M"                "L"
   "|{n,plural,=0 {none} =1 {one} other {#}}" "{n,plural,=0 {nada} =1 {uno} other {#}}"})

(swap! i18n/*loaded-translations* (fn [x] (assoc x "es-US" translations)))

(defn date [year month day hour min sec millis]
  #?(:clj  (java.util.Date. (- year 1900) month day hour min sec)
     :cljs (js/Date. year month day hour min sec millis)))

(specification "Base translation -- tr"
  (reset! i18n/*current-locale* "en-US")
  (assertions "returns the string it is passed if there is no translation"
    (tr "Hello") => "Hello"
    "returns message key if current-locale is en-US"
    (tr "Hi") => "Hi")
  (reset! i18n/*current-locale* "es-US")
  (assertions
    "returns message key if no translation map is found for the locale"
    (tr "Hello") => "Hello"
    "returns message key if translation is not found in the translation map"
    (tr "Hi") => "Ola"
    "Returns an error-marker string if anything but a literal string is used"
    (str/starts-with? (tr 4) "ERROR: tr requires a literal string") => true
    (str/starts-with? (tr (i18n/current-locale)) "ERROR: tr requires a literal string") => true
    (str/starts-with? (tr :keyword) "ERROR: tr requires a literal string") => true
    "   (error markers include namespace and line number)"
    (tr 4) =fn=> (fn [s] (re-matches #".*on line [1-9][0-9]* in fulcro.i18n-spec" s))))

(specification "Base translation lambda -- trlambda"
  (reset! i18n/*current-locale* "en-US")
  (assertions
    "returns a function, which when called, does the translation."
    ((trlambda "Hello")) => "Hello"))

(specification "Message translations with context"
  (assertions
    "Requires literal strings for both arguments"
    (str/starts-with? (trc 1 4) "ERROR:") => true
    (str/starts-with? (trc 1 "msg") "ERROR:") => true
    (str/starts-with? (trc "c" 4) "ERROR:") => true
    "   (error message includes line and namespace)"
    (trc 1 4) =fn=> (fn [s] (re-matches #".*on line [1-9][0-9]* in fulcro.i18n-spec" s))
    "Returns the message parameter if there is no translation"
    (trc "c" "m") => "m")
  (reset! i18n/*current-locale* "en-US")
  (assertions
    "Formats in en-US locale"
    (trc "Abbreviation for Monday" "M") => "M")
  (reset! i18n/*current-locale* "es-US")
  (assertions
    "Formats in an es locale"
    (trc "Abbreviation for Monday" "M") => "L"))

(specification "Message format translation -- trf"
  (reset! i18n/*current-locale* "en-US")
  (assertions "returns the string it is passed if there is no translation"
    (trf "Hello") => "Hello")
  (let [s "str"]
    (assertions
      "Requires that the format be a literal string"
      (str/starts-with? (trf s) "ERROR:") => true
      " (error include line and namespace)"
      (trf s) =fn=> (fn [s] (re-matches #".*on line [1-9][0-9]* in fulcro.i18n-spec" s))))
  (assertions
    "accepts a sequence of k/v pairs as arguments to the format"
    (trf "A {a} B {name}" :a 1 :name "Sam") => "A 1 B Sam"
    "accepts a single map as arguments to the format"
    (trf "A {a} B {name}" {:a 1 :name "Sam"}) => "A 1 B Sam")
  (assertions "formats numbers - US"
    (trf "{a, number}" :a 18349) => "18,349")
  (assertions
    "formats dates - US"
    (trf "{a, date, long}" :a (date 1990 3 1 13 45 22 0)) => "April 1, 1990"
    (trf "{a, date, medium}" :a (date 1990 3 1 13 45 22 0)) => "Apr 1, 1990"
    (trf "{a, date, short}" :a (date 1990 3 1 13 45 22 0)) => "4/1/90")
  (behavior "formats plurals - US"
    (assertions
      (trf "{n, plural, =0 {no apples} =1 {1 apple} other {# apples}}" :n 0) => "no apples"
      (trf "{n, plural, =0 {no apples} =1 {1 apple} other {# apples}}" :n 1) => "1 apple"
      (trf "{n, plural, =0 {no apples} =1 {1 apple} other {# apples}}" :n 2) => "2 apples"
      (trf "{n, plural, =0 {no apples} =1 {1 apple} other {# apples}}" :n 146) => "146 apples"))
  (reset! i18n/*current-locale* "de-DE")
  (assertions "formats numbers - Germany"
    (trf "{a, number}" :a 18349) => "18.349")
  (reset! i18n/*current-locale* "es-MX")
  (behavior "NOTE: JVM and various browsers all do this a little differently due to nuances of the various formatting implementations!"
    #?(:cljs (assertions
               "formats dates - Mexico (client-side)"
               (trf "{a, date, long}" :a (date 1990 3 1 13 45 22 0)) => "1 de abril de 1990"
               (trf "{a, date, medium}" :a (date 1990 3 1 13 45 22 0)) =fn=> (fn [s] (re-matches #"^1 .*abr.*" s))
               (trf "{a, date, short}" :a (date 1990 3 1 13 45 22 0)) => "1/4/90")
       :clj  (assertions
               "formats dates - Mexico (server-side)"
               (trf "{a, date, long}" :a (date 1990 3 1 13 45 22 0)) => "1 de abril de 1990"
               (trf "{a, date, medium}" :a (date 1990 3 1 13 45 22 0)) =fn=> (fn [s] (re-matches #"01/04/1990" s))
               (trf "{a, date, short}" :a (date 1990 3 1 13 45 22 0)) => "01/04/90")))
  (reset! i18n/*current-locale* "es-US")
  (behavior "formats plurals - Spanish"
    (assertions
      (trf "{n,plural,=0 {none} =1 {one} other {#}}" :n 1) => "uno"
      (trf "{n,plural,=0 {none} =1 {one} other {#}}" :n 2) => "2"
      (trf "{n,plural,=0 {none} =1 {one} other {#}}" :n 146) => "146")))

#?(:cljs
   (specification "Formatted Message Customization"
     (i18n/merge-custom-formats {:number {:USD {:style "currency", :currency "USD"}}})

     (assertions
       "works for cljs trf"
       (trf "Test: {amount,number,USD}" :amount 44.55543) => "Test: $44.56")))
