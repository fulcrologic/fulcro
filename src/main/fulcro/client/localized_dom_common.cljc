(ns fulcro.client.localized-dom-common
  (:refer-clojure :exclude [map meta time])
  (:require
    #?@(:cljs [[cljsjs.react]
           [cljsjs.react.dom]
           [cljsjs.react.dom.server]
           [goog.object :as gobj]])
    [fulcro.client.primitives :as prim]
    [clojure.string :as str])
  #?(:clj
     (:import (cljs.tagged_literals JSValue))))


(letfn [(remove-separators [s] (when s (str/replace s #"^[.#$]" "")))
        (get-tokens [k] (re-seq #"[#.$]?[^#.$]+" (name k)))]
  (defn- parse
    "Parse CSS shorthand keyword and return map of id/classes.

    (parse :.klass3#some-id.klass1.klass2)
    => {:id        \"some-id\"
        :classes [\"klass3\" \"klass1\" \"klass2\"]}"
    [k]
    (if k
      (let [tokens         (get-tokens k)
            id             (->> tokens (filter #(re-matches #"^#.*" %)) first)
            classes        (->> tokens (filter #(re-matches #"^\..*" %)))
            global-classes (into []
                             (comp
                               (filter #(re-matches #"^[$].*" %))
                               (clojure.core/map (fn [k] (-> k
                                                           name
                                                           (str/replace "$" "")))))
                             tokens)
            sanitized-id   (remove-separators id)]
        (when-not (re-matches #"^(\.[^.#$]+|#[^.#$]+|[$][^.#$]+)+$" (name k))
          (throw (ex-info "Invalid style keyword. It contains something other than classnames and IDs." {})))
        (cond-> {:global-classes global-classes
                 :classes        (into [] (keep remove-separators classes))}
          sanitized-id (assoc :id sanitized-id)))
      {})))

(defn- combined-classes
  "Takes a sequence of classname strings and a string with existing classes. Returns a string of these properly joined.

  classes-str can be nil or and empty string, and classes-seq can be nil or empty."
  [classes-seq classes-str]
  (str/join " " (if (seq classes-str) (conj classes-seq classes-str) classes-seq)))

(letfn [(pget [p nm dflt] (cond
                            #?@(:clj [(instance? JSValue p) (get-in p [:val nm] dflt)])
                            (map? p) (get p nm dflt)
                            #?@(:cljs [(object? p) (gobj/get p (name nm) dflt)])))
        (passoc [p nm v] (cond
                           #?@(:clj [(instance? JSValue p) (JSValue. (assoc (.-val p) nm v))])
                           (map? p) (assoc p nm v)
                           #?@(:cljs [(object? p) (do (gobj/set p (name nm) v) p)])))
        (pdissoc [p nm] (cond
                          #?@(:clj [(instance? JSValue p) (JSValue. (dissoc (.-val p) nm))])
                          (map? p) (dissoc p nm)
                          #?@(:cljs [(object? p) (do (gobj/remove p (name nm)) p)])))
        (strip-prefix [s] (str/replace s #"^[:.#$]*" ""))]
  (defn fold-in-classes
    "Update the :className prop in the given props to include the classes in the :classes entry of props. Works on js objects and CLJ maps as props.
    If using js props, they must be mutable."
    [props component]
    (if-let [extra-classes (pget props :classes nil)]
      (let [old-classes (pget props :className "")]
        (pdissoc
          (if component
            (let [clz         (prim/react-type component)
                  new-classes (combined-classes (clojure.core/map (fn [c]
                                                                    (let [c (some-> c name)]
                                                                      (cond
                                                                        (nil? c) ""
                                                                        (str/starts-with? c ".") (fulcro-css.css/local-class clz (strip-prefix c))
                                                                        (str/starts-with? c "$") (strip-prefix c)
                                                                        :else c))) extra-classes) old-classes)]
              (passoc props :className new-classes))
            (let [new-classes (combined-classes (clojure.core/map strip-prefix extra-classes) old-classes)]
              (passoc props :className new-classes)))
          :classes))
      props)))

(defn add-kwprops-to-props
  "Combine a hiccup-style keyword with props that are either a JS or CLJS map."
  [props kw]
  (let [{:keys [global-classes classes id] :or {classes []}} (parse kw)
        classes (vec (concat
                       (if prim/*parent*
                         (clojure.core/map #(fulcro-css.css/local-class (prim/react-type prim/*parent*) %) classes)
                         classes)
                       global-classes))]
    (fold-in-classes
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
            id (assoc :id id))))
      prim/*parent*)))


