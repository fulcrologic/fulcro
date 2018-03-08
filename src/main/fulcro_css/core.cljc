(ns fulcro-css.core
  #?(:cljs (:require-macros fulcro-css.core))
  (:require [cljs.tagged-literals]
            [clojure.string :as str]
            [garden.core :as g]
            [cljs.core]))


(defprotocol CSS
  (css [this] "Specifies the component-local CSS"))

(defn call-css [component]
  #?(:clj  ((:css (meta component)) component)
     :cljs (css component)))

(defn cssify
  "Replaces slashes and dots with underscore."
  [str] (when str (str/replace str #"[./]" "_")))

(defn fq-component [comp-class]
  #?(:clj  (if (nil? (meta comp-class))
             (str/replace (.getName comp-class) #"[_]" "-")
             (str (:component-ns (meta comp-class)) "/" (:component-name (meta comp-class))))
     :cljs (if-let [nm (.. comp-class -displayName)]
             nm
             "unknown/unknown")))

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
  #?(:clj  (-> m
             (assoc :className subclasses)
             (dissoc :class))
     :cljs (cljs.core/clj->js (-> m
                                (assoc :className subclasses)
                                (dissoc :class)))))
