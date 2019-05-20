(ns com.fulcrologic.fulcro.dom-common
  (:refer-clojure :exclude [map meta time mask select use set symbol filter])
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
          id           (->> tokens (clojure.core/filter #(re-matches #"^#.*" %)) first)
          classes      (->> tokens (clojure.core/filter #(re-matches #"^\..*" %)))
          sanitized-id (remove-separators id)]
      (when-not (re-matches #"^(\.[^.#]+|#[^.#]+)+$" (name k))
        (throw (ex-info "Invalid style keyword. It contains something other than classnames and IDs." {:item k})))
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

(def tags '#{a abbr address altGlyph altGlyphDef altGlyphItem animate animateColor animateMotion animateTransform area
             article aside audio b base bdi bdo big blockquote body br button canvas caption circle cite clipPath code
             col colgroup color-profile cursor data datalist dd defs del desc details dfn dialog discard div dl dt
             ellipse em embed feBlend feColorMatrix feComponentTransfer feComposite feConvolveMatrix feDiffuseLighting
             feDisplacementMap feDistantLight feDropShadow feFlood feFuncA feFuncB feFuncG feFuncR feGaussianBlur
             feImage feMerge feMergeNode feMorphology feOffset fePointLight feSpecularLighting feSpotLight feTile feTurbulence
             fieldset figcaption figure filter font font-face font-face-format font-face-name font-face-src font-face-uri
             footer foreignObject form g glyph glyphRef h1 h2 h3 h4 h5 h6 hatch hatchpath head header hkern hr html
             i iframe image img input ins kbd keygen label legend li line linearGradient link main map mark marker mask
             menu menuitem mesh meshgradient meshpatch meshrow meta metadata meter missing-glyph
             mpath nav noscript object ol optgroup option output p param path pattern picture polygon polyline pre progress q radialGradient
             rect rp rt ruby s samp script section select set small solidcolor source span stop strong style sub summary
             sup svg switch symbol table tbody td text textPath textarea tfoot th thead time title tr track tref tspan
             u ul unknown use var video view vkern wbr})

(defn gen-docstring
  "Helper function for generating the docstrings for generated dom functions and
  macros."
  [tag client-side?]
  (str "Returns a " (if client-side? "React" "server side")
       " DOM element. Can be invoked in several ways\n\n"

       "These two are made equivalent at compile time\n"
       "(" tag " \"hello\")\n"
       (str "(" tag " nil \"hello\")\n")
       "\n"

       "These two are made equivalent at compile time\n"
       "(" tag " {:onClick f} \"hello\")\n"
       "(" tag " #js {:onClick f} \"hello\")\n"
       "\n"

       "There is also a shorthand for CSS id and class names\n"
       "(" tag " :#the-id.klass.other-klass \"hello\")\n"
       "(" tag " :#the-id.klass.other-klass {:onClick f} \"hello\")"))

(defn classes->str
  [classes]
  (str/join " "
    (into []
      (comp
        (mapcat (fn [entry]
                  (cond
                    (keyword? entry) (:classes (parse entry))
                    (string? entry) [entry])))
        (clojure.core/filter string?))
      classes)))

(defn interpret-classes
  "Interprets the :classes prop, reducing any non-nil elements into :className. returns the new props with updated
  :className and no :classes"
  [props]
  (if (and (map? props) (contains? props :classes))
    (let [new-class-strings (classes->str (:classes props))
          strcls            (or (:className props) "")
          final-classes     (str strcls " " new-class-strings)]
      (-> props
        (assoc :className final-classes)
        (dissoc :classes)))
    props))
