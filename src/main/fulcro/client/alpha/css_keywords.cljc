(ns fulcro.client.alpha.css-keywords
  "Simple parser for the optional CSS shorthand in dom API.
  Inspired by https://github.com/r0man/sablono"
  (:require [clojure.string :as str]
    #?(:cljs [goog.object :as gobj])))

(defn remove-separators [s]
  (when s
    (str/replace s #"^[.#]" "")))

(defn get-tokens [k]
  (re-seq #"[#.]?[^#.]+" (name k)))

(defn parse
  "Parse CSS shorthand keyword and return map of id/classes.

  (parse :.klass3#some-id.klass1.klass2)
  => {:id        \"some-id\"
      :classes [\"klass3\" \"klass1\" \"klass2\"]}"
  [k]
  (if k
    (let [tokens       (get-tokens k)
          id           (->> tokens (filter #(re-matches #"^#.*" %)) first)
          classes      (->> tokens (filter #(re-matches #"^\..*" %)))
          sanitized-id (remove-separators id)]
      (when-not (re-matches #"^(\.[^.#]+|#[^.#]+)+$" (name k))
        (throw (ex-info "Invalid style keyword. It contains something other than classnames and IDs." {})))
      (cond-> {:classes (into []
                          (keep remove-separators classes))}
        sanitized-id (assoc :id sanitized-id)))
    {}))

(defn- combined-classes
  "Takes a sequence of classname strings and a string with existing classes. Returns a string of these properly joined.

  classes-str can be nil or and empty string, and classes-seq can be nil or empty."
  [classes-seq classes-str]
  (str/join " " (if (seq classes-str) (conj classes-seq classes-str) classes-seq)))

(defn combine
  "Combine a hiccup-style keyword with props that are either a JS or CLJS map."
  [props kw]
  (let [{:keys [classes id] :or {classes []}} (parse kw)]
    (if #?(:clj false :cljs (or (nil? props) (object? props)))
      #?(:clj  props
         :cljs (let [props            (gobj/clone props)
                     existing-classes (gobj/get props "className")]
                 (when (seq classes) (gobj/set props "className" (combined-classes classes existing-classes)))
                 (when id (gobj/set props "id" id))
                 props))
      (let [existing-classes (:className props)]
        (cond-> (or props {})
          (seq classes) (assoc :className (combined-classes classes existing-classes))
          id (assoc :id id))))))
