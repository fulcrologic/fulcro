(ns om-css.core
  #?(:clj
     (:use com.rpl.specter))
  (:require [clojure.string :as str]
            [garden.core :as g]
    #?(:clj
            [cljs.tagged-literals])
            [om.next :as om])
  #?(:clj
     (:import (cljs.tagged_literals JSValue))))

#?(:cljs
   (defprotocol CSS
     (css [this] "Specifies the component-local CSS")))

#?(:cljs
   (defn cssify
     "Replaces slashes and dots with underscore."
     [str] (str/replace str #"[./]" "_")))

#?(:cljs
   (defn local-kw
     "Generate a keyword for a localized CSS class for use in Garden CSS syntax as a localized component classname keyword."
     ([comp-class]
      (keyword (str "." (cssify (pr-str comp-class)))))
     ([comp-class nm]
      (keyword (str "." (cssify (pr-str comp-class)) "__" (name nm))))))

#?(:cljs
   (defn local-class
     "Generates a string name of a localized CSS class. This function combines the fully-qualified name of the given class
     with the (optional) specified name."
     ([comp-class]
      (str (cssify (pr-str comp-class))))
     ([comp-class nm]
      (str (cssify (pr-str comp-class)) "__" (name nm)))))

#?(:cljs
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
           (implements? CSS i) (let [rules (css i)]
                                 (if (every? vector? rules)
                                   (into acc rules)
                                   (conj acc rules)))
           (vector? i) (conj acc i)
           :else acc)) [] items)))

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
       (set! (.-innerHTML style-ele) (g/css (css root-component)))
       (.setAttribute style-ele "id" id)
       (.appendChild (.-body js/document) style-ele))))

#?(:clj
   (defmacro apply-css
     "Localizes class names specified in DOM elements as keywords or vectors of keywords set in the :class property
     of their attributes map and outputs them as a proper :className string. Starting a keyword's name with `$` will
     prevent localization.

        (render [this] (apply-css ClassName (dom/div #js { :class [:p :$r] } ...)))

     will result in:

        (render [this] (dom/div #js { :className \"namespace_ClassName_p r\"  } ...))
     "
     [class body]
     (letfn [(localize-classnames
               ; Replace any class names in map m with localized versions (names prefixed with $ will be mapped to root)
               [class m]
               (let [m (.val m)
                     subclass (:class m)
                     entry (fn [c]
                             (let [cn (name c)]
                               (if (str/starts-with? cn "$")
                                 (str/replace cn #"^[$]" "")
                                 `(app.css/local-class ~class ~cn))))
                     subclasses (if (vector? subclass)
                                  (apply list (reduce (fn [acc c] (conj acc (entry c) " ")) ['str] subclass))
                                  (entry subclass))]
                 (list 'cljs.core/clj->js (-> m
                                              (assoc :className subclasses)
                                              (dissoc :class)))))
             (defines-class?
               ; Check if the given element is a JS map that has a :class key.
               [ele] (and (= JSValue (type ele))
                          (map? (.val ele))
                          (contains? (.val ele) :class)))
             ]
       (transform (walker defines-class?) (partial localize-classnames class) body))))
