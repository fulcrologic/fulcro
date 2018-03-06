(ns fulcro.client.alpha.css-parser
  "Simple parser for the optional CSS shorthand in dom API.
  Inspired by https://github.com/r0man/sablono"
  (:require [clojure.string :as str]))

(defn remove-separators [s]
  (when s
    (str/replace s #"^[.#]" "")))

(defn get-tokens [k]
  (re-seq #"[#.]?[^#.]+" (name k)))

(defn parse
  "Parse CSS shorthand keyword and return map of id/classes.

  (parse :.klass3#some-id.klass1.klass2)
  => {:id        \"some-id\"
      :className \"klass3 klass1 klass2\"}"
  [k]
  (if k
    (let [tokens       (get-tokens k)
          id           (->> tokens (filter #(re-matches #"^#.*" %)) first)
          classes      (->> tokens (filter #(re-matches #"^\..*" %)))
          sanitized-id (remove-separators id)]
      (cond-> {:className (str/join " " (map remove-separators classes))}
        sanitized-id (assoc :id sanitized-id)))
    {}))
