(ns untangled.ui.bootstrap
  (:require [om.dom :as dom]
            [clojure.string :as str]
            [clojure.set :as set]))

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

(defn address
  "Formats an address"
  [name & {:keys [street street2 city-state phone email]}]
  (let [brs      (repeatedly #(dom/br nil))
        children (filter identity
                   (list
                     street
                     street2
                     city-state
                     (when phone
                       (list (dom/abbr #js {:title "Phone"} "P:") phone))))]
    (apply dom/address nil
      (dom/strong nil name) (dom/br nil)
      (interleave children brs))))

(defn quotation
  "Render a block quotation with optional citation and source.

  align - :right or :left (default)
  credit - The name of the person.
  source - The place where it was said/written, you supply the joining preposition.
  children - The DOM element(s) (typicaly a p) that represent the body of the quotation
  "
  [{:keys [align credit source] :as attrs} & children]
  (let [attrs   (cond-> (dissoc attrs :align :credit :source)
                  (= :right align) (assoc :className (str "blockquote-reverse " (:className attrs))))
        content (concat children [(dom/footer nil credit " " (dom/cite nil source))])]
    (apply dom/blockquote (clj->js attrs) content)))

(defn plain-ul
  "Render an unstyled unordered list"
  [attrs & children]
  (let [attrs (update attrs :className #(str " list-unstyled"))]
    (apply dom/ul (clj->js attrs) children)))

(defn inline-ul
  "Render an inline unordered list"
  [attrs & children]
  (let [attrs (update attrs :className #(str " list-inline"))]
    (apply dom/ul (clj->js attrs) children)))

(def table-styles #{:striped :bordered :condensed :hover})

(defn table
  "Renders a table. attrs can contain any normal react attributes, including :className.

  Extended attributes that you can include are:

  styles - A set of one or more of: #{:striped :bordered :condensed :hover}
  "
  [{:keys [className styles] :as attrs} & children]
  (let [style-classes (str/join " " (map #(str "table-" (name %)) (set/intersection table-styles styles)))
        classes       (str (when className (str className " ")) "table " style-classes)]
    (apply dom/table (clj->js (assoc attrs :className classes)) children)))

(defn labeled-input
  "An input with a label. All of the attrs apply to the input itself. You must supply a type and id for the
  field to work correctly. DO NOT USE for checkbox or radio.

  The additional attributes are supported:

  :split - A number from 1 to 11. The number of `sm` columns to allocate to the field label. If not specified, the
  label will be above the input. The parent must use the `.form-horizontal` class.
  :help - A string. If supplied, displayed as the help text for the field. NOT shown if warning, error, or success are set.

  You should only ever supply zero or one of the following:
  :warning - A boolean or string. If set as a string, that content replaces the help text.
  :error - A boolean or string. If set as a string, that content replaces the help text.
  :success - A boolean or string. If set as a string, that content replaces the help text.
  "
  [{:keys [id type className placeholder split help warning error success] :as attrs} label]
  (let [state-class        (cond
                             error " has-error"
                             warning " has-warning"
                             success " has-success"
                             :else "")
        form-group-classes (str "form-group" state-class)
        help               (first (keep identity [error warning success help]))
        split-right        (- 12 split)
        help-id            (str id "-help")
        attrs              (cond-> (dissoc attrs :split :help :warning :error :success)
                             help (assoc :aria-describedby help-id)
                             :always (update :className #(str " form-control")))]
    (cond
      (int? split) (dom/div #js {:className form-group-classes}
                     (dom/label #js {:className (str "control-label col-sm-" split) :htmlFor id} label)
                     (dom/div #js {:className (str "col-sm-" split-right)}
                       (dom/input (clj->js attrs))
                       (when help (dom/span #js {:id help-id :className "help-block"} help))))
      :else (dom/div #js {:className form-group-classes}
              (dom/label #js {:className "control-label" :htmlFor id} label)
              (dom/input (clj->js attrs))
              (when help (dom/span #js {:id help-id :className "help-block"} help))))))
