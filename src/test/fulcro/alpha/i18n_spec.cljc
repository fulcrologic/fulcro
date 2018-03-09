(ns fulcro.alpha.i18n-spec
  (:require [fulcro-spec.core :refer [specification behavior provided assertions when-mocking]]
    #?@(:cljs [yahoo.intl-messageformat-with-locales])
            [clojure.string :as str]
            [fulcro.alpha.i18n :as i18n :refer [tr trf trc]]
            [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.server-render :as ssr]
            [fulcro.client.dom :as dom]
            [fulcro.logging :as log])
  #?(:clj
     (:import (com.ibm.icu.text MessageFormat)
              (java.util Locale))))

(def es-locale
  {::i18n/locale       :es
   ::i18n/translations {["" "Hi"]                                      "Ola"
                        ["Abbreviation for Monday" "M"]                "L"
                        ["" "{n,plural,=0 {none} =1 {one} other {#}}"] "{n,plural,=0 {nada} =1 {uno} other {#}}"}})

(def locale-with-no-translations
  {::i18n/locale       :en-US
   ::i18n/translations {}})

(def bad-locale
  {::i18n/locale       :es
   ::i18n/translations {["" "Hi"] ""}})

(defn date [year month day hour min sec millis]
  #?(:clj  (java.util.Date. (- year 1900) month day hour min sec)
     :cljs (js/Date. year month day hour min sec millis)))

(defn deflt-format [{:keys [::i18n/localized-format-string
                      ::i18n/locale ::i18n/format-options]}]
  #?(:cljs
     (let [locale-str (name locale)
           formatter  (js/IntlMessageFormat. localized-format-string locale-str)]
       (.format formatter (clj->js format-options)))

     :clj
     (let [locale-str (name locale)]
       (try
         (let [formatter (new MessageFormat localized-format-string (Locale/forLanguageTag locale-str))]
           (.format formatter format-options))
         (catch Exception e
           (log/error "Formatting failed!" e)
           "???")))))

(specification "Base translation -- tr"
  (assertions
    "returns the message key if there is no translation"
    (i18n/with-locale deflt-format locale-with-no-translations (tr "Hello")) => "Hello"
    "returns message key if translation is an empty string"
    (i18n/with-locale deflt-format bad-locale (tr "Hi")) => "Hi"
    "returns message key if no entry is found in the translations"
    (i18n/with-locale deflt-format es-locale (tr "Hello")) => "Hello"
    "Returns an error-marker string if anything but a literal string is used"
    (str/starts-with? (tr 4) "ERROR: tr requires a literal string") => true
    (str/starts-with? (tr map) "ERROR: tr requires a literal string") => true
    (str/starts-with? (tr :keyword) "ERROR: tr requires a literal string") => true
    "   (error markers include namespace and line number)"
    (tr 4) =fn=> (fn [s] (re-matches #".*on line [1-9][0-9]* in fulcro.*" s))))


(specification "Message translations with context"
  (assertions
    "Requires literal strings for both arguments"
    (str/includes? (trc 1 4) "literal string") => true
    (str/includes? (trc 1 "msg") "literal string") => true
    (str/includes? (trc "c" 4) "literal string") => true
    "   (error message includes line and namespace)"
    (trc 1 4) =fn=> (fn [s] (re-matches #".*on line [1-9][0-9]* in fulcro.*" s))
    "Returns the message parameter if there is no translation"
    (trc "c" "m") => "m")
  (assertions
    "Formats in en-US locale"
    (trc "Abbreviation for Monday" "M") => "M")
  (assertions
    "Formats in an es locale"
    (i18n/with-locale deflt-format es-locale (trc "Abbreviation for Monday" "M")) => "L"))

(specification "Message format translation -- trf"
  (i18n/with-locale deflt-format locale-with-no-translations
    (assertions "returns the string it is passed if there is no translation"
      (trf "Hello") => "Hello")
    (let [s "str"]
      (assertions
        "Requires that the format be a literal string"
        (str/includes? (trf s) "literal string") => true
        " (error includes line and namespace)"
        (trf s) =fn=> (fn [s] (re-matches #".*on line [1-9][0-9]* in fulcro.*" s))))
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
    (assertions "formats numbers - Germany"
      (i18n/with-locale deflt-format {::i18n/locale :de} (trf "{a, number}" :a 18349)) => "18.349")
    (i18n/with-locale deflt-format (assoc es-locale ::i18n/locale :es-MX)
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
      (behavior "formats plurals - Spanish"
        (assertions
          (trf "{n,plural,=0 {none} =1 {one} other {#}}" :n 1) => "uno"
          (trf "{n,plural,=0 {none} =1 {one} other {#}}" :n 2) => "2"
          (trf "{n,plural,=0 {none} =1 {one} other {#}}" :n 146) => "146")))))

(specification "An undefined locale"
  (i18n/with-locale deflt-format nil
    (behavior "uses the in-code translation"
      (assertions
        "tr"
        (tr "Hello") => "Hello"
        "trc"
        (trc "context" "Hello") => "Hello"
        "trf"
        (trf "Hi, {name}" :name "Tony") => "Hi, Tony"
        "trf map-based"
        (trf "Hi, {name}" {:name "Tony"}) => "Hi, Tony"))))

#?(:clj
   (specification "Locale loading from PO files."
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
                                            ["" "Hello"]                "Hola"}})))))

#?(:clj
   (defsc Child [this props]
     {:query         [:ui/checked?]
      :initial-state {:ui/checked? false}}
     (dom/div nil
       (dom/p nil (trf "Hello, {name}" {:name "Sam"}))
       (dom/p nil (trf "It is {n,date}" {:n (java.util.Date.)}))
       (dom/p nil (trc "Gender abbreviation" "M"))
       (tr "Hello"))))

#?(:clj
   (def ui-child (prim/factory Child)))

#?(:clj
   (defsc Root [this {:keys [child locale-selector]}]
     {:query         [{:locale-selector (prim/get-query i18n/LocaleSelector)}
                      {::i18n/current-locale (prim/get-query i18n/Locale)}
                      {:child (prim/get-query Child)}]
      :initial-state {:child                {}
                      ::i18n/current-locale {:locale :en :name "English" :translations {}}
                      :locale-selector      {:locales [{:locale :en :name "English"}
                                                       {:locale :es :name "Espanol"}
                                                       {:locale :de :name "Deutsch"}]}}}
     (dom/div nil
       (i18n/ui-locale-selector locale-selector)
       (ui-child child))))

#?(:clj
   (defn message-formatter [{:keys [::i18n/localized-format-string ::i18n/locale ::i18n/format-options]}]
     localized-format-string))

#?(:clj
   (specification "Locale override in SSR"
     (let [initial-tree     (prim/get-initial-state Root {})
           es-locale        (i18n/load-locale "fulcro/alpha" :es)
           tree-with-locale (assoc initial-tree ::i18n/current-locale es-locale)
           ui-root          (prim/factory Root)
           output-html      (i18n/with-locale message-formatter es-locale
                              (dom/render-to-str (ui-root tree-with-locale)))
           bad-locale-html  (i18n/with-locale message-formatter nil
                              (dom/render-to-str (ui-root initial-tree)))]
       (assertions
         "Renders properly even if the locale isn't set correctly"
         (str/includes? bad-locale-html "It is {n,date}") => true
         "Renders properly with the overridden locale"
         (str/includes? output-html "Es {n, date}") => true))))
