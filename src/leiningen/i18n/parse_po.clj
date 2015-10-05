(ns leiningen.i18n.parse-po
  (:require [clojure.string :as str]))

(defn group-chunks
  "Subdivide a translation chunk into a list of translation components, placing msgctxt/msgid/msgstr components into
  individual vectors with their corresponding values."
  [translation-chunk]
  (reduce (fn [acc line]
            (let [unescaped-newlines (str/replace line #"\\n" "\n")]
              (if (re-matches #"^msg.*" line)
                (conj acc [unescaped-newlines])
                (update-in acc [(dec (count acc))] conj unescaped-newlines))))
          [] translation-chunk))

(defn join-quoted-strings
  "Join quoted strings together.

  Parameters:
  * `strings` - a vector of strings, where the string may contain a string in \"\"

  Returns a string which is a concatenation of the quoted substrings in the vector."
  [strings]
  (reduce (fn [acc quoted-string]
            (str acc (last (re-matches #"(?ms)^.*\"(.*)\"" quoted-string)))) "" strings))

(defn group-translations
  "Group the content of a .po file by translations.

  Parameters:
  * `fname` - the path to a .po file on disk

  Returns a vector of corresponding translation components."
  [fname]
  (let [fstring (slurp fname)
        trans-chunks (rest (clojure.string/split fstring #"(?ms)\n\n"))
        grouped-chunks (map clojure.string/split-lines trans-chunks)
        comment? #(re-matches #"^#.*" %)
        uncommented-chunks (map #(remove comment? %) grouped-chunks)
        keyed-chunks (map group-chunks uncommented-chunks)]
    (if (empty? keyed-chunks) nil keyed-chunks)))

(defn map-translation-components
  "Map translation components to translation values.

  Parameters:
  * `acc` - an accumulator into which translation key/values will be placed
  * `grouped-trans-chunk` - a vector of translation components

  Used to reduce over a vector of `grouped-trans-chunk`'s, returns a map of :msgid/:msgctxt/:msgstr to their respective
  string values."
  [acc grouped-trans-chunk]
  (reduce (fn [mapped-translation trans-subcomponent]
            (let [key (->> trans-subcomponent first (re-matches #"^(msg[a-z]+) .*$") last keyword)
                  value (join-quoted-strings trans-subcomponent)]
              (assoc mapped-translation key value))) acc grouped-trans-chunk))

(defn map-translations
  "Map translated strings to lookup keys.

  Parameters:
  * `fname` - the path to a .po file on disk

  Returns a map of msgstr values to msgctxt|msgid string keys."
  [fname]
  (let [translation-groups (group-translations fname)
        mapped-translations (reduce (fn [trans-maps translation]
                                      (conj trans-maps (map-translation-components {} translation)))
                                    [] translation-groups)]
    (reduce (fn [acc translation]
              (assoc acc (str (:msgctxt translation) "|" (:msgid translation)) (:msgstr translation)))
            {} mapped-translations)))