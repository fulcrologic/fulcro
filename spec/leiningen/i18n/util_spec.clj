(ns leiningen.i18n.util-spec
  (:require [leiningen.i18n-spec-fixtures :as fixture]
            [leiningen.i18n.util :as e]
            [clojure.test :refer (is deftest run-tests testing do-report)]
            [clojure.java.shell :refer [sh]]
            [smooth-spec.core :refer (specification behavior provided assertions)]))

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