(ns leiningen.i18n
  (:require [clojure.java.shell :refer [sh]]
            [leiningen.core.main :as lmain]
            [leiningen.cljsbuild :refer [cljsbuild]]
            [clojure.string :as str]
            [leiningen.i18n.code-gen :as cg]
            [leiningen.i18n.parse-po :as parse]
            [leiningen.i18n.util :as util]
            [clojure.pprint :as pp]))

(def msgs-dir-path "i18n/msgs")
(def messages-pot-path (str msgs-dir-path "/messages.pot"))
(def compiled-js-path "i18n/out/compiled.js")

(defn- po-path [po-file] (str msgs-dir-path "/" po-file))

(defn- puke [msg]
  (lmain/warn msg)
  (lmain/abort))

(defn configure-i18n-build
  "
  Create an in-memory clsjbuild configuration.

  Parameters:
  * `build` - a [:cljsbuild :builds] map

  Returns a new cljsbuild configuration that will ensure all cljs is compiled into a single JS file, from which we will
  extract translatable strings.
  "
  [build]
  (let [compiler-config (assoc (:compiler build) :output-dir "i18n/out"
                                                 :optimizations :whitespace
                                                 :output-to compiled-js-path)]
    (assoc build :id "i18n" :compiler compiler-config)))



(defn lookup-modules
  "
  Check if the production cljs build contains a :modules configuration map.

  Parameters:
  * `project` - a leiningen project map
  * `locales` - a list of locale strings

  If the production cljs build has :modules, return nil, else return the suggested :modules configuration.
  "
  [project locales]
  (let [ns (util/translation-namespace project)
        build (util/get-cljsbuild (get-in project [:cljsbuild :builds]))
        ]
    (if (-> build :compiler (contains? :modules))
      nil
      (let [output-dir (:output-dir (:compiler build))
            js-file #(str output-dir "/" % ".js")
            name (:name project)
            main-name (str name ".main")
            main {:output-to (js-file name)
                  :entries   #{main-name}}
            modules (reduce #(assoc %1
                              (keyword %2) {:output-to (js-file %2)
                                            :entries   #{(str ns "." %2)}}) {} locales)
            modules-with-main (assoc modules :main main)]
        (-> build
            (update-in [:compiler] dissoc :main)
            (assoc-in [:compiler :modules] modules-with-main)
            (assoc-in [:compiler :optimizations] :advanced))))))

(defn deploy-translations
  "This subtask converts translated .po files into locale-specific .cljs files for runtime string translation."
  [project]
  (let [replace-hyphen #(str/replace % #"-" "_")
        trans-ns (util/translation-namespace project)
        output-dir (util/cljs-output-dir trans-ns)
        po-files (util/find-po-files msgs-dir-path)
        default-lc (util/default-locale project)
        locales (map util/clojure-ize-locale po-files)
        locales-inc-default (conj locales default-lc)
        default-lc-translation-path (str output-dir "/" (replace-hyphen default-lc) ".cljs")
        default-lc-translations (cg/wrap-with-swap :namespace trans-ns :locale default-lc :translation {})
        locales-code-string (cg/gen-locales-ns project locales)
        locales-path (str output-dir "/locales.cljs")
        default-locale-code-string (cg/gen-default-locale-ns trans-ns default-lc)
        default-locale-path (str output-dir "/default_locale.cljs")]
    (sh "mkdir" "-p" output-dir)
    (cg/write-cljs-translation-file default-locale-path default-locale-code-string)
    (if (some #{default-lc} locales)
      (cg/write-cljs-translation-file locales-path locales-code-string)
      (let [locales-code-string (cg/gen-locales-ns project locales-inc-default)]
        (cg/write-cljs-translation-file locales-path locales-code-string)
        (cg/write-cljs-translation-file default-lc-translation-path default-lc-translations)))
    (lmain/warn "Configured project for default locale:" default-lc)

    (doseq [po po-files]
      (let [locale (util/clojure-ize-locale po)
            translation-map (parse/map-translations (po-path po))
            cljs-translations (cg/wrap-with-swap
                                :namespace trans-ns :locale locale :translation translation-map)
            cljs-trans-path (str output-dir "/" (replace-hyphen locale) ".cljs")]
        (cg/write-cljs-translation-file cljs-trans-path cljs-translations)))

    (lmain/warn "Deployed translations for the following locales:" locales)

    (if-let [modules-map (lookup-modules project locales-inc-default)]
      (do (lmain/warn
            "
            No :modules configuration detected for dynamically loading translations!
            Your production cljsbuild should look something like this:
            ")
          (lmain/warn (pp/write modules-map :stream nil)
                      "
                      ")))))

(defn extract-strings
  "This subtask extracts strings from your cljs files that should be translated."
  [project]
  (if (util/gettext-missing?)
    (puke "The xgettext and msgcat commands are not installed, or not on your $PATH.")
    (if (util/dir-missing? msgs-dir-path)
      (puke "The i18n/msgs directory is missing in your project! Please create it.")
      (let [cljsbuilds-path [:cljsbuild :builds]
            builds (get-in project cljsbuilds-path)
            cljs-prod-build (util/get-cljsbuild builds)
            i18n-build (configure-i18n-build cljs-prod-build)
            i18n-project (assoc-in project cljsbuilds-path [i18n-build])
            po-files-to-merge (util/find-po-files msgs-dir-path)]

        (cljsbuild i18n-project "once" "i18n")
        (sh "xgettext" "--from-code=UTF-8" "--debug" "-k" "-ktr:1" "-ktrc:1c,2" "-ktrf:1" "-o" messages-pot-path
            compiled-js-path)
        (doseq [po po-files-to-merge]
          (sh "msgcat" "--no-wrap" messages-pot-path (po-path po) "-o" (po-path po)))))))

(defn i18n
  "A plugin which automates your i18n string translation workflow"
  {:subtasks [#'extract-strings #'deploy-translations]}
  ([project]
   (puke "Bad you!"))
  ([project subtask]
   (case subtask
     "extract-strings" (extract-strings project)
     "deploy-translations" (deploy-translations project)
     (puke (str "Unrecognized subtask: " subtask)))))
