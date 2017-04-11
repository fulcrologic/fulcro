(ns om-css.core-spec
  (:require #?(:cljs [untangled-spec.core :refer-macros [specification assertions behavior]]
               :clj  [untangled-spec.core :refer [specification assertions behavior]])
            [om-css.core :as css :refer [localize-classnames]]
            [om.next :as om :refer [defui]]
            [om.dom :as dom]))

(defui Child
  static css/CSS
  (css [this]
    (let [p (css/local-kw Child :p)]
      [p {:font-weight 'bold}]))
  Object
  (render [this]
    (let [{:keys [id label]} (om/props this)]
      (dom/div nil "Hello"))))

(defui Child2
  static css/CSS
  (css [this]
    (let [p (css/local-kw Child2 :p)
          p2 (css/local-kw Child2 :p2)]
      [[p {:font-weight 'bold}] [p2 {:font-weight 'normal}]]))
  Object
  (render [this]
    (let [{:keys [id label]} (om/props this)]
      (dom/div nil "Hello"))))

(specification "CSS local classes"
  (behavior "can be generated for a class"
    (assertions
      "with a keyword"
      (css/local-class Child :root) => "om-css_core-spec_Child__root"
      "with a string"
      (css/local-class Child "root") => "om-css_core-spec_Child__root"
      "with a symbol"
      (css/local-class Child 'root) => "om-css_core-spec_Child__root")))

(specification "CSS merge"
  (assertions
    "Allows a component to specify a single rule"
    (css/css-merge Child) => [[:.om-css_core-spec_Child__p {:font-weight 'bold}]]
    "Allows a component to specify multiple rules"
    (css/css-merge Child2) => [[:.om-css_core-spec_Child2__p {:font-weight 'bold}]
                               [:.om-css_core-spec_Child2__p2 {:font-weight 'normal}]]
    "Allows component combinations"
    (css/css-merge Child Child2) => [[:.om-css_core-spec_Child__p {:font-weight 'bold}]
                                     [:.om-css_core-spec_Child2__p {:font-weight 'bold}]
                                     [:.om-css_core-spec_Child2__p2 {:font-weight 'normal}]]
    "Merges rules in with component css"
    (css/css-merge Child [:a {:x 1}] Child2) => [[:.om-css_core-spec_Child__p {:font-weight 'bold}]
                                                 [:a {:x 1}]
                                                 [:.om-css_core-spec_Child2__p {:font-weight 'bold}]
                                                 [:.om-css_core-spec_Child2__p2 {:font-weight 'normal}]]))

(defrecord X [])

(defui Boo
  static css/CSS
  (css [this] [:a {:x 1}]))

(specification "apply-css macro"
  (assertions
    "Converts :class entries to localized names for record types"
    (localize-classnames X (pr-str [:a {:b [:c {:d #js {:class :a}}]}])) => #?(:cljs "[:a {:b [:c {:d #js {:className \"om-css_core-spec_X__a\"}}]}]"
                                                                               :clj "[:a {:b [:c {:d {:className \"om-css_core-spec_X__a\"}}]}]")
    "Converts :class entries to localized names for defui types"
    (localize-classnames Boo (pr-str [:a {:b [:c {:d #js {:class :a}}]}])) => #?(:cljs "[:a {:b [:c {:d #js {:className \"om-css_core-spec_Boo__a\"}}]}]"
                                                                                 :clj "[:a {:b [:c {:d {:className \"om-css_core-spec_Boo__a\"}}]}]")))
