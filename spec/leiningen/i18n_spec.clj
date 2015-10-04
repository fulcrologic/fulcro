(ns leiningen.i18n-spec
  (:require [clojure.test :refer (is deftest run-tests testing do-report)]
            [smooth-spec.core :refer (specification behavior provided assertions)]
            [leiningen.i18n-spec-fixtures :as fixture]
            [clojure.java.shell :refer [sh]]
            [leiningen.i18n :as e]))


(let [which "which"
      xg "xgettext"
      mc "msgcat"
      ls "ls"
      dir "dir"
      po-dir "de.po\nfoofah.txt\ntldr.md\nja_JP.po"]

  (specification "the default-locale function"
                 (behavior "returns the default-locale configured in the project"
                           (assertions
                             (e/default-locale
                               {:untangled-i18n {:default-locale "es-MX"}}) => "es-MX"))

                 (behavior "defaults to untangled.translations"
                           (assertions
                             (e/default-locale {}) => "en-US")))

  (specification "the translation-namespace function"
                 (behavior "returns the namespace configured in the project"
                           (assertions
                             (e/translation-namespace
                               {:untangled-i18n {:translation-namespace 'i18n}}) => 'i18n))

                 (behavior "defaults to untangled.translations"
                           (assertions
                             (e/translation-namespace {}) => 'untangled.translations)))

  (specification "the find-po-files function"
                 (provided "when no files are found"
                           (sh ls dir) =1x=> {:out ""}
                           (behavior "returns an empty list"
                                     (assertions
                                       (e/find-po-files dir) => '())))

                 (provided "when some po files are found among other files"
                           (sh ls dir) =1x=> {:out po-dir}
                           (behavior "returns a list of the po files"
                                     (assertions
                                       (e/find-po-files dir) => '("de.po" "ja_JP.po")))))


  (specification "the gettext-missing? function"
                 (provided "when xgettext and msgcat are installed"
                           (sh which xg) =1x=> {:exit 0}
                           (sh which mc) =1x=> {:exit 0}
                           (behavior "returns false"
                                     (assertions (e/gettext-missing?) => false)))

                 (provided "when xgettext and msgcat are not installed"
                           (sh which xg) =1x=> {:exit 1}
                           (sh which mc) =1x=> {:exit 1}
                           (behavior "returns true"
                                     (assertions (e/gettext-missing?) => true)))

                 (provided "when xgettext is installed, but msgcat is not"
                           (sh which xg) =1x=> {:exit 0}
                           (sh which mc) =1x=> {:exit 1}
                           (behavior "returns true"
                                     (assertions (e/gettext-missing?) => true))))

  (specification "the cljs-output-dir function"
                 (behavior "returns a path string to the translation-namespace in src"
                           (assertions
                             (e/cljs-output-dir 'i18n) => "src/i18n"
                             (e/cljs-output-dir 'i18n.some.more-namespace) => "src/i18n/some/more-namespace"))))

(specification "the cljsbuild-prod-build? function"
               (behavior "returns false if :id is not \"production\""
                         (assertions
                           (e/cljs-prod-build? fixture/dev-build) => false))

               (behavior "returns a build with :id \"production\""
                         (assertions
                           (e/cljs-prod-build? fixture/prod-build) => fixture/prod-build)))

(specification "the get-cljsbuild function"
               (behavior "returns a production build"
                         (assertions
                           (e/get-cljsbuild fixture/cljs-builds) => fixture/prod-build)))

(specification "the configure-i18n-build function"
               (let [i18n-build (e/configure-i18n-build fixture/prod-build)]
                 (behavior "assigns :id \"i18n\" to the build"
                           (assertions
                             (:id i18n-build) => "i18n"))

                 (behavior "enables :optimizations :whitespace on the build"
                           (assertions
                             (get-in i18n-build [:compiler :optimizations]) => :whitespace))))

(specification
  "the gen-locales-ns function"
  (provided
    "when given a project file, emits a code string"
    (e/translation-namespace project) => "survey.i18n"
    (e/get-cljsbuild whatever) => {:compiler {:output-dir "res/pub/js/compiled/out"}}
    (let [code-string (e/gen-locales-ns {} '("fc-KY"))]

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
    (let [code-string (e/gen-default-locale-ns 'survey.i18n "fc-KY")]

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

(specification
  "the lookup-modules function"
  (let [proj-with-modules {:cljsbuild {:builds [{:id       "production"
                                                 :compiler {:modules {}}}]}}

        proj-without-modules {:name           "survey"
                              :cljsbuild      {:builds [{:id       "production"
                                                         :compiler {:main       "survey.main"
                                                                    :output-dir "res/pub/js/comp/out"}}]}
                              :untangled-i18n {:translation-namespace 'survey.i18n
                                               :default-locale        "fc-KY"}}]

    (behavior
      "when build does not contain modules"
      (behavior
        "adds :optimizations :advanced to the :compiler"
        (assertions
          (-> (e/lookup-modules proj-without-modules '()) :compiler :optimizations) => :advanced))

      (behavior
        "removes the top-level :main"
        (assertions
          (contains? (:compiler (e/lookup-modules proj-without-modules '())) :main) => false))

      (behavior
        "returns a suggested :main module in the map"
        (assertions
          (->
            (e/lookup-modules
              proj-without-modules '()) :compiler :modules :main) => {:output-to "res/pub/js/comp/out/survey.js"
                                                                      :entries   #{"survey.main"}}))
      (behavior
        "returns locale modules in the map"
        (assertions
          (->
            (e/lookup-modules
              proj-without-modules '("en-US" "fc-KY")) :compiler :modules :fc-KY) => {:output-to "res/pub/js/comp/out/fc-KY.js"
                                                                                      :entries   #{"survey.i18n.fc-KY"}})))
    (behavior
      "when build contains modules"
      (behavior
        "returns nil"
        (assertions
          (e/lookup-modules proj-with-modules '()) => nil)))))


