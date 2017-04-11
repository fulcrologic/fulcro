(ns untangled.client.css
  #?(:cljs (:require-macros untangled.client.css))
  (:require [cljs.tagged-literals]
            [clojure.string :as str]
            [com.rpl.specter :as sp]
            [garden.core :as g]
            [om.next :as om]
            [cljs.core]))


(defprotocol CSS
  (css [this] "Specifies the component-local CSS"))

(defn call-css [component]
  #?(:clj ((:css (meta component)) component)
     :cljs (css component)))

(defn cssify
  "Replaces slashes and dots with underscore."
  [str] (str/replace str #"[./]" "_"))

(defn fq-component [comp-class]
 #?(:clj (if (nil? (meta comp-class))
            (str/replace (.getName comp-class) #"[_]" "-")
            (str (:component-ns (meta comp-class)) "/" (:component-name (meta comp-class))))
    :cljs (pr-str comp-class)))

(defn local-kw
  "Generate a keyword for a localized CSS class for use in Garden CSS syntax as a localized component classname keyword."
  ([comp-class]
   (keyword (str "." (cssify (fq-component comp-class)))))
  ([comp-class nm]
   (keyword (str "." (cssify (fq-component comp-class)) "__" (name nm)))))

(defn local-class
  "Generates a string name of a localized CSS class. This function combines the fully-qualified name of the given class
     with the (optional) specified name."
  ([comp-class]
   (str (cssify (fq-component comp-class))))
  ([comp-class nm]
   (str (cssify (fq-component comp-class)) "__" (name nm))))

(defn CSS?
  #?(:cljs {:tag boolean})
  [x]
  #?(:clj  (if (fn? x)
             (some? (-> x meta :css))
             (extends? CSS (class x)))
     :cljs (implements? CSS x)))

(defn css-merge
  "Merge together the CSS of components that implement the CSS interface and other literal CSS entries.
     This function can be used to simply chain together rules of Garden syntax:

     (css-merge [:c {:color :black}] [:d {:color :red}])

     which really just makes it a nested vector; however, you can intermix components that implement the CSS interface:

     (css-merge [:c {:color :black}] CSSComponent [:d {:color :red}])

     which themselves can have single rules, or vectors of rules:

     (defrecord MyCss []
       css/CSS
       (css [this] [ [:rule { ... }] [:rule2 { ... }] ]))

     (defui SomeUI
       static css/CSS
       (css [this] [ [:rule { ... }] [:rule2 { ... }] ]))

     (defui Root
       static css/CSS
       (css [this] (css-merge SomeUI MyCss))
       ...)
     "
  [& items]
  (reduce
   (fn [acc i]
     (cond
       (CSS? i) (let [rules (call-css i)]
                  (if (every? vector? rules)
                    (into acc rules)
                    (conj acc rules)))
       (vector? i) (conj acc i)
       :else acc)) [] items))

#?(:cljs
   (defn remove-from-dom "Remove the given element from the DOM by ID"
     [id]
     (if-let [old-element (.getElementById js/document id)]
       (let [parent (.-parentNode old-element)]
         (.removeChild parent old-element)))))

#?(:cljs
   (defn upsert-css
     "(Re)place the STYLE element with the provided ID on the document's DOM  with the co-located CSS of the specified component."
     [id root-component]
     (remove-from-dom id)
     (let [style-ele (.createElement js/document "style")]
       (set! (.-innerHTML style-ele) (g/css (call-css root-component)))
       (.setAttribute style-ele "id" id)
       (.appendChild (.-body js/document) style-ele))))

(defn set-classname
 [m subclasses]
 #?(:clj (-> m
             (assoc :className subclasses)
             (dissoc :class))
    :cljs (cljs.core/clj->js (-> m
                                 (assoc :className subclasses)
                                 (dissoc :class)))))

#?(:clj
  (defmacro localize-classnames
   "Localizes class names specified in DOM elements as keywords or vectors of keywords set in the :class property
       of their attributes map and outputs them as a proper :className string. Starting a keyword's name with `$` will
       prevent localization.

          (render [this] (localize-classnames ClassName (dom/div #js { :class [:p :$r] } ...)))

       will result in:

          (render [this] (dom/div #js { :className \"namespace_ClassName_p r\"  } ...))
       "
   [class body]
   (letfn [(localize-classnames
             ;; Replace any class names in map m with localized versions (names prefixed with $ will be mapped to root)
             [class m]
             (let [m (if (map-entry? m) m (.val m))
                   subclass (if (map-entry? m) (.val m) (:class m))
                   entry (fn [c]
                           (let [cn (name c)]
                             (if (str/starts-with? cn "$")
                               (str/replace cn #"^[$]" "")
                               `(om-css.core/local-class ~class ~cn))))
                   subclasses (if (vector? subclass)
                                (apply list (reduce (fn [acc c] (conj acc (entry c) " ")) ['str] subclass))
                                (entry subclass))]
                (if (map-entry? m)
                   [:className subclasses]
                   `(set-classname ~m ~subclasses))))
           (defines-class?
             ;; Check if the given element is a JS map or map-entry that has a :class key.
             [ele]
             (if #?(:clj (and (map-entry? ele) (= :class (key ele))))
                true
                (and (= cljs.tagged_literals.JSValue (type ele))
                     (map? (.val ele))
                     (contains? (.val ele) :class))))]
    (sp/transform (sp/walker defines-class?) (partial localize-classnames class) body))))
