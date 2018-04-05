(ns fulcro.client.dom-common
  (:refer-clojure :exclude [map meta time mask select use])
  (:require
    [clojure.string :as str]
    #?@(:cljs ([cljsjs.react]
                [cljsjs.react.dom]
                [goog.object :as gobj]))))

(defn- remove-separators [s]
  (when s
    (str/replace s #"^[.#]" "")))

(defn- get-tokens [k]
  (re-seq #"[#.]?[^#.]+" (name k)))

(defn- parse
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

(defn add-kwprops-to-props
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

(def tags '#{a abbr address area article aside audio b base bdi bdo big blockquote body br button canvas caption cite code
             col colgroup data datalist dd del details dfn dialog div dl dt em embed fieldset figcaption figure footer form h1
             h2 h3 h4 h5 h6 head header hr html i iframe img input ins kbd keygen label legend li link main
             map mark menu menuitem meta meter nav noscript object ol optgroup option output p param picture pre progress q rp rt
             ruby s samp script section select small source span strong style sub summary sup table tbody td textarea
             tfoot th thead time title tr track u ul use var video wbr circle clipPath ellipse g line mask path pattern
             polyline rect svg text defs linearGradient polygon radialGradient stop tspan})


