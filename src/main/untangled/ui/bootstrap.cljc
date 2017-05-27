(ns untangled.ui.bootstrap
  (:require [om.dom :as dom]))

#?(:clj (defn- clj->js [m] m))

;; Bootstrap CSS and Components for Untangled

(defn- div-with-class [cls attrs children]
  (let [addl-classes (get attrs :className)
        attrs        (assoc attrs :className (str cls " " addl-classes))]
    (apply dom/div (clj->js attrs) children)))

(defn- p-with-class [cls attrs children]
  (let [addl-classes (get attrs :className)
        attrs        (assoc attrs :className (str cls " " addl-classes))]
    (apply dom/p (clj->js attrs) children)))

(defn container
  "Top-level container for bootstrap grid content. This is a responsive fixed-width container. See also container-fluid."
  [attrs & children] (div-with-class "container" attrs children))

(defn container-fluid
  "Top-level container for bootstrap grid content. This is a responsive full-width container. See also container."
  [attrs & children] (div-with-class "container-fluid" attrs children))

(defn row
  "Generate a layout row. This is a div container for a row in a 12-wide grid responsive layout.
  Rows should contain layout columns generated with the `col` function of this namespace.

  The properties are normal DOM attributes as a cljs map and can include standard React DOM properties.
  "
  [props & children]
  (div-with-class "row" props children))

(defn col
  "Output a div that represents a column in the 12-column responsive grid.

  Any React props are allowed. The following special one pertain to the column:

  xs The width of the column on xs screens
  sm The width of the column on sm screens
  md The width of the column on md screens
  lg The width of the column on lg screens
  xs-offset The offset of the column on xs screens
  sm-offset The offset of the column on sm screens
  md-offset The offset of the column on md screens
  lg-offset The offset of the column on lg screens
  "
  [{:keys [className xs sm md lg xs-offset sm-offset md-offset lg-offset] :as props} & children]
  (let [classes (cond-> (:className props)
                  xs (str " col-xs-" xs)
                  sm (str " col-sm-" sm)
                  md (str " col-md-" md)
                  lg (str " col-lg-" lg)
                  xs-offset (str " col-xs-offset-" xs-offset)
                  sm-offset (str " col-sm-offset-" sm-offset)
                  md-offset (str " col-md-offset-" md-offset)
                  lg-offset (str " col-lg-offset-" lg-offset))
        attrs   (-> props
                  (dissoc :xs :sm :md :lg :xs-offset :sm-offset :md-offset :lg-offset)
                  (assoc :className classes)
                  clj->js)]
    (apply dom/div attrs children)))

(defn lead
  "Generates a lead paragraph with an increased font size."
  [attrs & children]
  (p-with-class "lead" attrs children))

