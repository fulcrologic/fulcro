(ns om-css.core-spec
  (:require [untangled-spec.core :refer-macros [specification assertions behavior]]
            [om-css.core :as css]
            [om.next :as om :refer-macros [defui]]
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
    (css/css-merge Child) => [[:.app_css-spec_Child__p {:font-weight 'bold}]]
    "Allows a component to specify multiple rules"
    (css/css-merge Child2) => [[:.app_css-spec_Child2__p {:font-weight 'bold}]
                               [:.app_css-spec_Child2__p2 {:font-weight 'normal}]]
    "Allows component combinations"
    (css/css-merge Child Child2) => [[:.app_css-spec_Child__p {:font-weight 'bold}]
                                     [:.app_css-spec_Child2__p {:font-weight 'bold}]
                                     [:.app_css-spec_Child2__p2 {:font-weight 'normal}]]
    "Merges rules in with component css"
    (css/css-merge Child [:a {:x 1}] Child2) => [[:.app_css-spec_Child__p {:font-weight 'bold}]
                                                 [:a {:x 1}]
                                                 [:.app_css-spec_Child2__p {:font-weight 'bold}]
                                                 [:.app_css-spec_Child2__p2 {:font-weight 'normal}]]
    ))

(defrecord X [])

#_(specification "apply-css macro"
  (assertions
    "Converts :class entries to localized names"
    (css/apply-css X (pr-str [:a {:b [:c {:d #js {:class :a}}]}])) => "[:a {:b [:c {:d {:className \"app_css-spec_X__a\"}}]}]"
    ))



