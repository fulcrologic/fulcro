(ns leiningen.i18n-spec
  (:require [clojure.test :refer (is deftest run-tests testing do-report)]
            [smooth-spec.core :refer (specification behavior provided assertions)]
            [leiningen.i18n-spec-fixtures :as fixture]
            [smooth-spec.report :as report]
            [clojure.java.shell :refer [sh]]
            [leiningen.i18n :as e]))

(let [which "which"
      xg "xgettext"
      mc "msgcat"
      ls "ls"
      dir "dir"
      po-dir "de.po\nfoofah.txt\ntldr.md\nja_JP.po"
      ]

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
                                     (assertions (e/gettext-missing?) => true)))))

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

;(report/with-smooth-output (run-tests 'leiningen.i18n-spec))