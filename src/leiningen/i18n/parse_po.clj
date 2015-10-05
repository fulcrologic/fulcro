(ns leiningen.i18n.parse-po
  (:require [clojure.string :as str]))

(defn group-chunks [translation-chunk]
  (reduce (fn [acc line]
            (let [unescaped-newlines (str/replace line #"\\n" "\n")]
              (if (re-matches #"^msg.*" line)
                (conj acc [unescaped-newlines])
                (update-in acc [(dec (count acc))] conj unescaped-newlines))))
          [] translation-chunk))

(defn join-quoted-strings [strings]
  (reduce (fn [acc quoted-string]
            (str acc (last (re-matches #"(?ms)^.*\"(.*)\"" quoted-string)))) "" strings))

(defn group-translations [fname]
  (let [fstring (slurp fname)
        trans-chunks (rest (clojure.string/split fstring #"(?ms)\n\n"))
        grouped-chunks (map clojure.string/split-lines trans-chunks)
        comment? #(re-matches #"^#.*" %)
        uncommented-chunks (map #(remove comment? %) grouped-chunks)
        keyed-chunks (map group-chunks uncommented-chunks)]
    (if (empty? keyed-chunks) nil keyed-chunks)))

(defn inline-strings [acc grouped-trans-chunk]
  (reduce (fn [mapped-translation trans-subcomponent]
            (let [key (->> trans-subcomponent first (re-matches #"^(msg[a-z]+) .*$") last keyword)
                  value (join-quoted-strings trans-subcomponent)]
              (assoc mapped-translation key value))) acc grouped-trans-chunk))

(defn map-translations [fname]
  (let [translation-groups (group-translations fname)
        mapped-translations (reduce (fn [trans-maps translation]
                                      (conj trans-maps (inline-strings {} translation)))
                                    [] translation-groups)]
    (reduce (fn [acc translation]
              (assoc acc (str (:msgctxt translation) "|" (:msgid translation)) (:msgstr translation)))
            {} mapped-translations)))