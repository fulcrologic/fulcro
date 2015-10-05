(ns leiningen.i18n.code-gen-spec
  (:require [leiningen.i18n.code-gen :as u]
            [leiningen.i18n.util :as util]
            [clojure.test :refer (is deftest run-tests testing do-report)]
            [smooth-spec.core :refer (specification behavior provided assertions)]
            ))

(specification "the wrap-with-swap function emits a code string"
               (let [code-string (u/wrap-with-swap :namespace 'i18n :locale "fr-CA" :translation "{\"fizz\" \"buzz\"}")
                     import-re #"(?ms).*\(:import.*(goog.module.ModuleManager).*"
                     ns-re #"(?ms)^(\(ns i18n.fr-CA).*"
                     import-match (last (re-matches import-re code-string))
                     ns-match (last (re-matches ns-re code-string))]
                 (behavior "that begins with a namespace delcaration"
                           (assertions
                             ns-match => "(ns i18n.fr-CA"))
                 (behavior "which also imports goog's ModuleManager."
                           (assertions
                             import-match => "goog.module.ModuleManager")))

               (let [code-string (u/wrap-with-swap :locale "fr-CA" :translation "{\"fizz\" \"buzz\"}")
                     module-re #"(?ms).*(\(-> goog.module.ModuleManager \.getInstance \(\.setLoaded \"fr-CA\"\)\)).*"
                     module-match (last (re-matches module-re code-string))
                     atom-re #"(?ms)^.*(untangled.i18n.core/\*loaded-translations\*).*"
                     atom-match (last (re-matches atom-re code-string))]
                 (behavior "that ends with a default :atom-name"
                           (assertions
                             atom-match => "untangled.i18n.core/*loaded-translations*"))
                 (behavior "and also a call to ModuleManager.setLoaded"
                           (assertions
                             module-match => "(-> goog.module.ModuleManager .getInstance (.setLoaded \"fr-CA\"))"))))

(specification
  "the gen-locales-ns function"
  (provided
    "when given a project file, emits a code string"
    (util/translation-namespace project) => "survey.i18n"
    (util/get-cljsbuild whatever) => {:compiler {:output-dir "res/pub/js/compiled/out"}}
    (let [code-string (u/gen-locales-ns {} '("fc-KY"))]

      (behavior
        "that begins with configurable namespace declaration"
        (assertions
          (last (re-matches #"(?ms)^\((ns\n survey.i18n.locales).*" code-string)) => "ns\n survey.i18n.locales"))

      (behavior
        "that contains a javascript map of locales to corresponding .js files in the output directory"
        (assertions
          (last (re-matches #"(?ms).*(\{\"fc-KY\" \"/js/compiled/out/fc-KY.js\"\}).*"
                            code-string)) => "{\"fc-KY\" \"/js/compiled/out/fc-KY.js\"}")
        (behavior
          "which is then def-once'd to the modules symbol"
          (assertions
            (last (re-matches #"(?ms).*(\(defonce modules #js).*" code-string)) => "(defonce modules #js")))

      (behavior
        "that contains a javascript map of locales to an empty vector"
        (assertions
          (last (re-matches #"(?ms).*(\{\"fc-KY\" \[\]\}).*"
                            code-string)) => "{\"fc-KY\" []}")
        (behavior
          "which is then def-once'd to the module-info symbol"
          (assertions
            (last (re-matches #"(?ms).*(\(defonce module-info #js).*"
                              code-string)) => "(defonce module-info #js")))

      (behavior "that creates a defonce with ^:export annotation for goog's ModuleLoader"
                (assertions
                  (last (re-matches #"(?ms).*(\(defonce \^:export loader \().*" code-string)) => "(defonce ^:export loader ("))
      (behavior "that defines a set-locale function"
                (assertions
                  (last (re-matches #"(?ms).*(\(defn set-locale).*" code-string)) => "(defn set-locale")))))

(specification
  "the gen-default-locale-ns function"
  (behavior
    "when given a namespace and a default locale, emits a code string"
    (let [code-string (u/gen-default-locale-ns 'survey.i18n "fc-KY")]

      (behavior
        "that begins with a configurable namespace declaration"
        (assertions
          (last (re-matches #"(?ms)^(\(ns survey\.i18n\.default-locale).*" code-string)) => "(ns survey.i18n.default-locale")

        (behavior "which contains a :require of the default locale translation file"
                  (assertions
                    (last
                      (re-matches #"(?ms).*(\(:require survey\.i18n\.fc-KY).*" code-string)) => "(:require survey.i18n.fc-KY")))

      (behavior "that contains a reset on the *current-locale* atom"
                (assertions
                  (last
                    (re-matches #"(?ms).*\n(\(reset!.*\"fc-KY\"\)).*" code-string)) => "(reset! i18n/*current-locale* \"fc-KY\")"))

      (behavior "that contains a swap on the *loaded-translations* atom"
                (assertions
                  (last
                    (re-matches #"(?ms).*(\(swap!.*translations\)\)).*"
                                code-string)) => "(swap! i18n/*loaded-translations* #(assoc % :fc-KY survey.i18n.fc-KY/translations))")))))
