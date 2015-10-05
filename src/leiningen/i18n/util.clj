(ns leiningen.i18n.util
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(defn cljs-output-dir [namespace]
  (let [path-from-namespace (str/replace (str namespace) #"\." "/")]
    (str "src/" path-from-namespace)))

(defn default-locale [project]
  (if-let [locale (get-in project [:untangled-i18n :default-locale])]
    locale
    "en-US"))

(defn find-po-files [msgs-dir-path]
  (filter #(.endsWith % ".po")
          (clojure.string/split-lines
            (:out (sh "ls" msgs-dir-path)))))

(defn gettext-missing? []
  (let [xgettext (:exit (sh "which" "xgettext"))
        msgcat (:exit (sh "which" "msgcat"))]
    (or (> xgettext 0) (> msgcat 0))))

(defn dir-missing? [dir]
  (-> (sh "ls" "-d" dir)
      (get :exit)
      (> 0)))

(defn cljs-prod-build?
  [build]
  (if (= (:id build) "production") build false))

(defn get-cljsbuild [builds]
  (some #(cljs-prod-build? %)
        builds))

(defn translation-namespace [project]
  (if-let [ns (get-in project [:untangled-i18n :translation-namespace])]
    ns
    (symbol 'untangled.translations)))

(defn clojure-ize-locale [po-filename]
  (-> po-filename
      (str/replace #"^([a-z]+_*[A-Z]*).po$" "$1")
      (str/replace #"_" "-")))