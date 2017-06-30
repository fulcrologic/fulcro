(ns untangled.ui.bootstrap3
  (:require [om.dom :as dom]
            [om.next :as om :refer [defui]]
    #?(:clj
            js)
            [untangled.ui.elements :as ele]
            [untangled.events :as evt]
            [untangled.ui.html-entities :as ent]
            [untangled.i18n :refer [tr tr-unsafe]]
            [untangled.client.mutations :as m :refer [defmutation]]
            [clojure.string :as str]
            [clojure.set :as set]
            [untangled.client.core :as uc]
            [untangled.client.logging :as log]
            [untangled.client.util :as util]))

#?(:clj (defn- clj->js [m] m))



;; Bootstrap CSS and Components for Untangled

(defn- dom-with-class [dom-factory cls attrs children]
  (let [attrs (update attrs :className #(str cls " " %))]
    (apply dom-factory (clj->js attrs) children)))

(defn- div-with-class [cls attrs children] (dom-with-class dom/div cls attrs children))
(defn- p-with-class [cls attrs children] (dom-with-class dom/p cls attrs children))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; BASE CSS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
        classes       (str (when className (str className " ")) "table " style-classes)
        attrs         (-> attrs
                        (dissoc :styles)
                        (assoc :className classes)
                        clj->js)]
    (apply dom/table attrs children)))

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

(defn button
  "Render a button with optional styling

  kind - Optional. One of :primary, :success, :info, :warning, or :danger. Defaults to none (default).
  size - Optional. One of :xs, :sm, or :lg. Defaults to a normal size.
  as-block - Optional. Boolean. When true makes the button a block element.
  "
  [{:keys [kind size as-block] :as attrs} & children]
  {:pre [(or (nil? kind) (contains? #{:primary :success :info :warning :danger} kind))
         (or (nil? size) (contains? #{:xs :sm :lg} size))]}
  (let [incoming-classes (:className attrs)
        button-classes   (cond-> "btn"
                           kind (str " btn-" (name kind))
                           size (str " btn-" (name size))
                           (not kind) (str " btn-default")
                           as-block (str " btn-block")
                           incoming-classes (str " " incoming-classes))
        attrs            (-> attrs
                           (dissoc :kind :size :as-block)
                           (assoc :className button-classes)
                           clj->js)]
    (apply dom/button attrs children)))

(defn close-button [attrs]
  (let [addl-classes (:className attrs)
        classes      (cond-> "close"
                       addl-classes (str " " addl-classes))
        attrs        (assoc attrs :type "button" :aria-label "Close" :className classes)]
    (dom/button (clj->js attrs)
      (dom/span #js {:aria-hidden true} "\u00D7"))))

(defn img
  "Render an img tag with bootstrap classes.

  is-responsive - Boolean. Marks the image so that it scales to its container.
  shape - Optional. One of :rounded, :circle, or :thumbnail

  All other normal react attributes (including className) are allowed."
  [{:keys [is-responsive shape] :as attrs}]
  {:pre [(or (nil? shape) (contains? #{:rounded :circle :thumbnail} shape))]}
  (let [incoming-classes (:className attrs)
        button-classes   (cond-> ""
                           shape (str " " "img-" (name shape))
                           is-responsive (str " img-responsive")
                           incoming-classes (str " " incoming-classes))
        attrs            (-> attrs
                           (dissoc :is-responsive :shape)
                           (assoc :className button-classes)
                           clj->js)]
    (dom/img attrs)))

;; raw classes (for docstrings and autocomplete

(def text-left "A CSS class for Left aligned text." "text-left")
(def text-center "A CSS class for Center aligned text." "text-center")
(def text-right "A CSS class for Right aligned text." "text-right")
(def text-justify "A CSS class for Justified text." "text-justify")
(def text-nowrap "A CSS class for No wrap text." "text-nowrap")

(def text-lowercase "A css transform that will change encosed text to lowercased text." "text-lowercase")
(def text-uppercase "A css transform that will change encosed text to uppercased text." "text-uppercase")
(def text-capitalize "A css transform that will change encosed text to capitalized text." "text-capitalize")

(def text-muted "CSS class for a muted text color" "text-muted")
(def text-primary "A CSS classname for a primary text color" "text-primary")
(def text-success "A CSS classname for a success text color" "text-success")
(def text-info "A CSS classname for a info text color" "text-info")
(def text-warning "A CSS classname for a warning text color" "text-warning")
(def text-danger "A CSS classname for a danger text color" "text-danger")

(def bg-primary "A CSS classname for a contextual primary background color" "bg-primary")
(def bg-success "A CSS classname for a contextual success background color" "bg-success")
(def bg-info "A CSS classname for a contextual info background color" "bg-info")
(def bg-warning "A CSS classname for a contextual warning background color" "bg-warning")
(def bg-danger "A CSS classname for a contextual danger background color" "bg-danger")

(def pull-left "A CSS class for forcing a float" "pull-left")
(def pull-right "A CSS class for forcing a float" "pull-right")

(def center-block "A CSS class for centering a block element" "center-block")
(def clearfix "A CSS class used on the PARENT to clear floats within that parent." "clearfix")

(def show "A CSS class for BLOCK-level elements. Element affects flow and is visible." "show")
(def hidden "A CSS class for BLOCK-level elements. Element is not in flow." "hidden")
(def invisible "A CSS class for BLOCK-level elements. Element still affects flow." "invisible")

(def visible-xs-block "A responsive CSS class to show element according to screen size" "visible-xs-block")
(def visible-xs-inline "A responsive CSS class to show element according to screen size" "visible-xs-inline")
(def visible-xs-inline-block "A responsive CSS class to show element according to screen size" "visible-xs-inline-block")
(def visible-sm-block "A responsive CSS class to show element according to screen size" "visible-sm-block")
(def visible-sm-inline "A responsive CSS class to show element according to screen size" "visible-sm-inline")
(def visible-sm-inline-block "A responsive CSS class to show element according to screen size" "visible-sm-inline-block")
(def visible-md-block "A responsive CSS class to show element according to screen size" "visible-md-block")
(def visible-md-inline "A responsive CSS class to show element according to screen size" "visible-md-inline")
(def visible-md-inline-block "A responsive CSS class to show element according to screen size" "visible-md-inline-block")
(def visible-lg-block "A responsive CSS class to show element according to screen size" "visible-lg-block")
(def visible-lg-inline "A responsive CSS class to show element according to screen size" "visible-lg-inline")
(def visible-lg-inline-block "A responsive CSS class to show element according to screen size" "visible-lg-inline-block")

(def hidden-xs "A CSS class to hide the element according to screen size" "hidden-xs")
(def hidden-sm "A CSS class to hide the element according to screen size" "hidden-sm")
(def hidden-md "A CSS class to hide the element according to screen size" "hidden-md")
(def hidden-lg "A CSS class to hide the element according to screen size" "hidden-lg")


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Bootstrap Components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def glyph-icons #{:asterisk :plus :euro :eur :minus :cloud :envelope :pencil :glass :music :search :heart
                   :star :star-empty :user :film :th-large :th :th-list :ok :remove :zoom-in :zoom-out :off :signal
                   :cog :trash :home :file :time :road :download-alt :download :upload :inbox :play-circle
                   :repeat :refresh :list-alt :lock :flag :headphones :volume-off :volume-down :volume-up :qrcode
                   :barcode :tag :tags :book :bookmark :print :camera :font :bold :italic :text-height
                   :text-width :align-left :align-center :align-right :align-justify :list :indent-left :indent-right
                   :facetime-video :picture :map-marker :adjust :tint :edit :share :check :move :step-backward
                   :fast-backward :backward :play :pause :stop :forward :fast-forward :step-forward :eject
                   :chevron-left :chevron-right :plus-sign :minus-sign :remove-sign :ok-sign :question-sign
                   :info-sign :screenshot :remove-circle :ok-circle :ban-circle :arrow-left :arrow-right :arrow-up
                   :arrow-down :share-alt :resize-full :resize-small :exclamation-sign :gift :leaf :fire :eye-open
                   :eye-close :warning-sign :plane :calendar :random :comment :magnet :chevron-up :chevron-down
                   :retweet :shopping-cart :folder-close :folder-open :resize-vertical :resize-horizontal :hdd :bullhorn :bell
                   :certificate :thumbs-up :thumbs-down :hand-right :hand-left :hand-up :hand-down :circle-arrow-right :circle-arrow-left
                   :circle-arrow-up :circle-arrow-down :globe :wrench :tasks :filter :briefcase :fullscreen :dashboard
                   :paperclip :heart-empty :link :phone :pushpin :usd :gbp :sort :sort-by-alphabet
                   :sort-by-alphabet-alt :sort-by-order :sort-by-order-alt :sort-by-attributes :sort-by-attributes-alt :unchecked
                   :expand :collapse-down :collapse-up :log-in :flash :log-out :new-window :record :save :open :saved :import
                   :export :send :floppy-disk :floppy-saved :floppy-remove :floppy-save :floppy-open :credit-card :transfer
                   :cutlery :header :compressed :earphone :phone-alt :tower :stats :sd-video :hd-video
                   :subtitles :sound-stereo :sound-dolby :sound-5-1 :sound-6-1 :sound-7-1 :copyright-mark :registration-mark :cloud-download
                   :cloud-upload :tree-conifer :tree-deciduous :cd :save-file :open-file :level-up :copy :paste
                   :alert :equalizer :king :queen :pawn :bishop :knight :baby-formula :tent
                   :blackboard :bed :apple :erase :hourglass :lamp :duplicate :piggy-bank :scissors
                   :bitcoin :btc :xbt :yen :jpy :ruble :rub :scale :ice-lolly
                   :ice-lolly-tasted :education :option-horizontal :option-vertical :menu-hamburger :modal-window :oil :grain :sunglasses
                   :text-size :text-color :text-background :object-align-top :object-align-bottom :object-align-horizontal :object-align-left
                   :object-align-vertical :object-align-right :triangle-right :triangle-left :triangle-bottom :triangle-top
                   :console :superscript :subscript :menu-left :menu-right :menu-down :menu-up})

(defn glyphicon
  "Render a glyphicon in a span. Legal icon names are in b/glyph-icons.

  attrs will be added to the span's attributes.

  size - The size of the icon font. Defaults to 10pt.
  "
  [{:keys [size] :or {size "10pt"} :as attrs} icon]
  {:pre [(contains? glyph-icons icon)]}
  (let [attrs (-> attrs
                (dissoc :size)
                (assoc :aria-hidden true)
                (assoc :style #js {:fontSize size})
                (update :className #(str "glyphicon glyphicon-" (name icon)))
                clj->js)]
    (dom/span attrs "")))

(defn button-group
  "Groups nested buttons together in a horizontal row.

  `size` - (optional) can be :xs, :sm, or :lg.
  `kind` - (optional) can be :vertical or :justified"
  [{:keys [size kind] :as attrs} & children]
  (let [justified?  (= kind :justified)
        vertical?   (= kind :vertical)
        cls         (cond-> "btn-group"
                      justified? (str " btn-group-justified")
                      vertical? (str "-vertical")
                      size (str " btn-group-" (name size)))
        wrap-button (fn [ele] (if (ele/react-instance? "button" ele) (button-group {} ele) ele))
        attrs       (-> attrs (dissoc :size :kind) (assoc :role "group"))
        children    (if justified? (map #(wrap-button %) children) children)]
    (div-with-class cls attrs children)))

(defn button-toolbar
  "Groups button groups together as a toolbar, and a bit of space between each group"
  [attrs & children]
  (div-with-class "btn-toolbar" (assoc attrs :role "toolbar") children))

(defn breadcrumb-item
  "Define a breadcrumb.

   label - The label to show. You should internationalize this yourself.
   onClick - A function. What to do when the item is clicked. Not needed for the last item."
  ([label] {:label label :onClick identity})
  ([label onClick] {:label label :onClick onClick}))

(defn breadcrumbs
  "props - Properties to place on the top-level `ol`.
   items - a list of breadcrumb-item"
  [props & items]
  (let [attrs (update props :className #(str " breadcrumb"))]
    (dom/ol (clj->js attrs)
      (conj
        (mapv (fn [item] (dom/li #js {:key (:label item)} (dom/a #js {:onClick (:onClick item)} (:label item)))) (butlast items))
        (dom/li #js {:key (:label (last items)) :className "active"} (:label (last items)))))))

(defn pagination
  "Render a pagination control.

  props - A map of properties.
    size - One of :sm or :lg
  pagination-entries - One or more `pagination-entry`"
  [{:keys [size] :as props} & pagination-entries]
  (let [classes (cond-> (get props :className "")
                  :always (str "pagination")
                  size (str " pagination-" (name size)))
        attrs   (assoc props :className classes)]
    (dom/nav #js {:aria-label "Page Navigation"}
      (dom/ul (clj->js attrs) pagination-entries))))

(defn pagination-entry
  "Create an entry in a pagination control. Forward and back buttons can be rendered at either end with any label, but
  the untangled.ui.html-entities/raqao and laqao defs give a nicely sized font-based arrow."
  [{:keys [label disabled active onClick] :as props}]
  (let [onClick (if (and onClick (not disabled)) onClick identity)
        classes (cond-> (:className props)
                  disabled (str " disabled")
                  active (str " active"))
        attrs   (-> props
                  (assoc :className classes)
                  (dissoc :active :label :disabled :onClick))]
    (dom/li (clj->js attrs)
      (if (or (= label ent/raqao) (= label ent/laqao))
        (dom/a #js {:onClick onClick :aria-label (if (= ent/raqao label) "Next" "Previous")}
          (dom/span #js {:aria-hidden "true"} label))
        (dom/a #js {:onClick onClick}
          label (when active (dom/span #js {:className "sr-only"} " (current)")))))))


(defn pager
  "A light next/previous pair of controls. Use `pager-next` and `pager-previous` as the children of this."
  [props & children]
  (let [attrs (-> props
                (update :className #(str " pager"))
                clj->js)]
    (dom/nav #js {:aria-label "Page Navigation"}
      (apply dom/ul attrs children))))

(defn pager-next
  "Render a next button in a pager"
  [{:keys [onClick disabled]} & label-children]
  (dom/li #js {:key "next" :className (str "next" (when disabled " disabled"))} (apply dom/a #js {:onClick (or onClick identity)} label-children)))

(defn pager-previous
  "Render a previous button in a pager"
  [{:keys [onClick disabled]} & label-children]
  (dom/li #js {:key "prior" :className (str "previous" (when disabled " disabled"))} (apply dom/a #js {:onClick (or onClick identity)} label-children)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dropdowns
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def dropdown-table :bootstrap.dropdown/by-id)
(def dropdown-item-table :bootstrap.dropdown-item/by-id)

(defn dropdown-item
  "Define the state for an item of a dropdown."
  [id label] {::id id ::label label})
(defn dropdown-divider
  "Creates a divider between items. Must have a unique ID"
  [id] {::id id ::label ::divider})
(defn dropdown
  "Creates a dropdown's state. Create items with dropdown-item or dropdown-divider."
  [id label items] {::id id ::active-item nil ::label label ::items items ::open? false :type dropdown-table})

(defn dropdown-ident [id-or-props]
  (if (map? id-or-props)
    [(:type id-or-props) (::id id-or-props)]
    [dropdown-table id-or-props]))
(defn dropdown-item-ident [id-or-props]
  (if (map? id-or-props)
    (dropdown-item-ident (::id id-or-props))
    [dropdown-item-table id-or-props]))

(m/defmutation set-dropdown-open
  "Om Mutation. Set the open flag to true/false to open/close the dropdown."
  [{:keys [id open?]}]
  (action [{:keys [state]}]
    (let [kpath (conj (dropdown-ident id) ::open?)]
      (swap! state assoc-in kpath open?))))

(m/defmutation set-dropdown-item-active
  "Om Mutation. Set one of the items in a dropdown to active.

  id - the ID of the dropdown
  item-id - the ID of the dropdown item
  "
  [{:keys [id item-id]}]
  (action [{:keys [state]}]
    (swap! state update-in (dropdown-ident id) assoc ::active-item item-id)))

(defn- close-all-dropdowns-impl [dropdown-map]
  (reduce (fn [m id] (assoc-in m [id ::open?] false)) dropdown-map (keys dropdown-map)))

(m/defmutation close-all-dropdowns
  "Om Mutations: Close all dropdowns (globally)"
  [ignored]
  (action [{:keys [state]}]
    (swap! state update dropdown-table close-all-dropdowns-impl)))

(defui ^:once DropdownItem
  static om/IQuery
  (query [this] [::id ::label ::active? ::disabled? :type])
  static om/Ident
  (ident [this props] (dropdown-item-ident props))
  Object
  (render [this]
    (let [{:keys [::label ::id ::disabled?]} (om/props this)
          active?  (om/get-computed this :active?)
          onSelect (or (om/get-computed this :onSelect) identity)]
      (if (= ::divider label)
        (dom/li #js {:key id :role "separator" :className "divider"})
        (dom/li #js {:key id :className (cond-> ""
                                          disabled? (str " disabled")
                                          active? (str " active"))}
          (dom/a #js {:onClick (fn [evt]
                                 (.stopPropagation evt)
                                 (onSelect id)
                                 false)} (tr-unsafe label)))))))

(let [ui-dropdown-item-factory (om/factory DropdownItem {:keyfn ::id})]
  (defn ui-dropdown-item
    "Render a dropdown item. The props are the state props of the dropdown item. The additional by-name
    arguments:

    onSelect - The function to call when a menu item is selected
    active? - render this item as active
    "
    [props & {:keys [onSelect active?]}]
    (ui-dropdown-item-factory (om/computed props {:onSelect onSelect :active? active?}))))

(defui ^:once Dropdown
  static om/IQuery
  (query [this] [::id ::active-item ::label ::open? {::items (om/get-query DropdownItem)} :type])
  static om/Ident
  (ident [this props] (dropdown-ident props))
  Object
  (render [this]
    (let [{:keys [::id ::label ::active-item ::items ::open?]} (om/props this)
          {:keys [onSelect kind stateful?]} (om/get-computed this)
          active-item-label (->> items
                              (some #(and (= active-item (::id %)) %))
                              ::label)
          label             (if (and active-item-label stateful?) active-item-label label)
          onSelect          (fn [item-id]
                              (om/transact! this `[(close-all-dropdowns {}) (set-dropdown-item-active ~{:id id :item-id item-id})])
                              (when onSelect (onSelect item-id)))
          open-menu         (fn [evt]
                              (.stopPropagation evt)
                              (om/transact! this `[(close-all-dropdowns {}) (set-dropdown-open ~{:id id :open? (not open?)})])
                              false)]
      (button-group {:className (if open? "open" "")}
        (button {:className (cond-> "dropdown-toggle"
                              kind (str " btn-" (name kind))) :aria-haspopup true :aria-expanded open? :onClick open-menu}
          (tr-unsafe label) " " (dom/span #js {:className "caret"}))
        (dom/ul #js {:className "dropdown-menu"}
          (map #(ui-dropdown-item % :onSelect onSelect :active? (and stateful? (= (::id %) active-item))) items))))))

(let [ui-dropdown-factory (om/factory Dropdown {:keyfn ::id})]
  (defn ui-dropdown
    "Render a dropdown. The props are the state props of the dropdown. The additional by-name
    arguments:

    onSelect - The function to call when a menu item is selected
    stateful? - If set to true, the dropdown will remember the selection and show it.
    kind - The kind of dropdown. See `button`."
    [props & {:keys [onSelect kind stateful?] :as attrs}]
    (ui-dropdown-factory (om/computed props attrs))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; NAV (tabs/pills)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def nav-table :bootstrap.nav/by-id)
(def nav-link-table :bootstrap.navitem/by-id)

(defn nav-link
  "Creates a navigation link. ID must be globally unique. The label will be run through `tr-unsafe`, so it can be
  internationalized. "
  [id label disabled?]
  {::id id ::label label ::disbled? disabled? :type nav-link-table})

(defn nav-link-ident [id-or-props]
  (if (map? id-or-props)
    [(:type id-or-props) (::id id-or-props)]
    [nav-link-table id-or-props]))
(defn nav-ident [id-or-props]
  (if (map? id-or-props)
    [nav-table (::id id-or-props)]
    [nav-table id-or-props]))

(defn nav
  "Creates a navigation control.

  - kind - One of :tabs or :pills
  - layout - One of :normal, :stacked or :justified
  - active-link-id - Which of the nested links is the active one
  - links - A vector of `nav-link` or `dropdown` instances."
  [id kind layout active-link-id links]
  {::id id ::kind kind ::layout layout ::active-link-id active-link-id ::links links})

(defui ^:once NavLink
  static om/IQuery
  (query [this] [::id ::label ::disabled? :type])
  static om/Ident
  (ident [this props] (nav-link-ident props))
  Object
  (render [this]
    (let [{:keys [::id ::label ::disabled?]} (om/props this)
          {:keys [onSelect active?]} (om/get-computed this)]
      (dom/li #js {:role "presentation" :className (cond-> ""
                                                     active? (str " active")
                                                     disabled? (str " disabled"))}
        (dom/a #js {:onClick #(when onSelect (onSelect id))} (tr-unsafe label))))))

(def ui-nav-link (om/factory NavLink {:keyfn ::id}))

(defui ^:once NavItemUnion
  static om/Ident
  (ident [this {:keys [::id type]}] [type id])
  static om/IQuery
  (query [this] {dropdown-table (om/get-query Dropdown) nav-link-table (om/get-query NavLink)})
  Object
  (render [this]
    (let [{:keys [type ::items] :as child} (om/props this)
          {:keys [onSelect active-id active?] :as computed} (om/get-computed this)
          stateful? (some #(= active-id (::id %)) items)]
      (case type
        :bootstrap.navitem/by-id (ui-nav-link (om/computed child computed))
        :bootstrap.dropdown/by-id (ui-dropdown child :onSelect onSelect :stateful? stateful?)
        (dom/p nil "Unknown link type!")))))

(def ui-nav-item (om/factory NavItemUnion {:keyfn ::id}))

(defn set-active-nav-link*
  [state-map nav-id link-id]
  (update-in state-map (nav-ident nav-id) assoc ::active-link-id link-id))

(m/defmutation set-active-nav-link
  "Om Mutation: Set the active navigation link"
  [{:keys [id target]}]
  (action [{:keys [state]}]
    (swap! state set-active-nav-link* id target)))

(defui ^:once Nav
  static om/Ident
  (ident [this props] (nav-ident props))
  static om/IQuery
  (query [this] [::id ::kind ::layout ::active-link-id {::links (om/get-query NavItemUnion)}])
  Object
  (render [this]
    (let [{:keys [::id ::kind ::layout ::active-link-id ::links]} (om/props this)
          {:keys [onSelect]} (om/get-computed this)
          onSelect (fn [nav-id] (when onSelect (onSelect nav-id))
                     (om/transact! this `[(set-active-nav-link ~{:id id :target nav-id})]))]
      (dom/ul #js {:className (str "nav nav-" (name kind) (case layout :justified " nav-justified" :stacked " nav-stacked" ""))}
        (map #(ui-nav-item (om/computed % {:onSelect onSelect :active-id active-link-id :active? (= (::id %) active-link-id)})) links)))))

(let [nav-factory (om/factory Nav {:keyfn ::id})]
  (defn ui-nav
    "Render a nav, which should have state declared with `nav`.

    props - a cljs map of the data props
    onSelect - an optional named parameter to supply a function that is called when navigation is done.
    "
    [props & {:keys [onSelect]}]
    (nav-factory (om/computed props {:onSelect onSelect}))))

(defn label
  "Wraps children in an (inline) bootstrap label.

  props are standard DOM props, with support for the additional:

  kind - One of :primary, :success, :warning, :info, :danger. Defaults to :default."
  [{:keys [kind] :as props :or {kind :default}} & children]
  (let [classes (str "label label-" (name kind) " " (get props :className ""))
        attrs   (-> (dissoc props :kind)
                  (assoc :className classes))]
    (apply dom/span (clj->js attrs) children)))

(defn badge
  "Wraps children in an (inline) bootstrap badge.

  props are standard DOM props."
  [props & children]
  (let [classes (str "badge " (get props :className ""))
        attrs   (assoc props :className classes)]
    (apply dom/span (clj->js attrs) children)))

(defn jumbotron
  "Wraps children in a jumbotron"
  [props & children]
  (div-with-class "jumbotron" props children))

(defn alert
  "Renders an alert.

  Props can contain normal DOM props, and additionally:

  kind - The kind of alert: :info, :success, :warning, or :danger. Defaults to `:danger`.
  onClose - What to do when the close button is pressed. If nil, close will not be rendered."
  [{:keys [kind onClose] :as props} & children]
  (let [classes (str (:className props) " alert alert-dismissable alert-" (if kind (name kind) "danger"))
        attrs   (-> props
                  (dissoc :kind :onClose)
                  (assoc :role "alert" :className classes)
                  clj->js)]
    (apply dom/div attrs (if onClose
                           (close-button {:onClick onClose})
                           "") children)))


(defn caption
  "Renders content that has padding and a lightened color for the font."
  [props & children]
  (div-with-class "caption" props children))

(defn thumbnail
  "Renders a box around content. Typically used to double-box and image or generate Pinterest-style blocks."
  [props & children]
  (div-with-class "thumbnail" props children))

(defn progress-bar
  "Render's a progress bar from an input of the current progress (a number from 0 to 100).

  :current - The current value of progress (0 to 100)
  :animated? - Should the bar have a striped animation?
  :kind - One of :success, :warning, :danger, or :info

  Any classname or other properties included will be placed on the top-level div of the progress bar.
  "
  [{:keys [current kind animated?] :or {kind :info} :as props}]
  (let [attrs (dissoc props :current :kind :animated?)]
    (div-with-class "progress" attrs
      [(dom/div #js {:className (str "progress-bar progress-bar-" (name kind)
                                  (when animated? " progress-bar-striped active")) :role "progressbar" :aria-valuenow current
                     :aria-valuemin 0 :aria-valuemax 100 :style #js {:width (str current "%")}})])))

(defn panel
  "Render a panel. Use `panel-heading`, `panel-title`, `panel-body`, and `panel-footer` for elements of the panel.

  :kind is one of :primary, :success, :info, :warning, or :danger"
  [{:keys [kind] :or {kind :default} :as props} & children]
  (div-with-class (str "panel panel-" (name kind)) props children))

(defn panel-group
  "A wrapper for panels that visually groups them together."
  [props & children]
  (div-with-class "panel-group" props children))

(defn panel-heading
  "Render a heading area in a panel. Must be first. Optional."
  [props & children]
  (div-with-class "panel-heading" props children))

(defn panel-title
  "Render a title in a panel. Must be in a `panel-heading`."
  [props & children]
  (div-with-class "panel-title" props children))

(defn panel-body
  "Render children in the body of a panel. Not needed for tables or list groups. Should come after (optional) panel-heading."
  [props & children]
  (div-with-class "panel-body" props children))

(defn panel-footer
  "Render children in a footer of a panel."
  [props & children]
  (div-with-class "panel-footer" props children))

(defn well
  "Inset content.

  size - Optional to increase or decrease size. Can be :sm or :lg"
  [{:keys [size] :as props} & children]
  (div-with-class (str "well " (when size (str "well-" (name size)))) (dissoc props :size) children))

(defn get-abs-position
  "Get a map (with the keys :left and :top) that has the absolute position of the given DOM element."
  [ele]
  #?(:clj  {}
     :cljs (let [doc (.-ownerDocument ele)
                 win (or (.-defaultView doc) js/window)
                 box (.getBoundingClientRect ele)]
             {:width  (.-width box)
              :height (.-height box)
              :left   (+ (.-left box) (.-scrollX win))
              :top    (+ (.-top box) (.-scrollY win))})))

; we're using owner document to make sure this works in iframes, where js/document would be wrong
(defui RenderInBody
  Object
  (renderLayer [this]
    #?(:cljs (let [child (first (om/children this))
                   popup (.-popup this)]
               (.render js/ReactDOM child popup))))
  (componentDidMount [this]
    #?(:cljs (let [doc   (some-> (.-doc-element this) .-ownerDocument)
                   popup (when doc (.createElement doc "div"))]
               (set! (.-popup this) popup)
               (when doc (.appendChild (.-body doc) popup))
               (.renderLayer this))))
  (componentDidUpdate [this np ns] (.renderLayer this))
  (shouldComponentUpdate [this np ns] true)
  (componentWillUnmount [this]
    #?(:cljs (let [doc   (some-> (.-doc-element this) .-ownerDocument)
                   popup (.-popup this)]
               (.unmountComponentAtNode js/ReactDOM (.-popup this))
               (when doc
                 (.removeChild (.-body doc) (.-popup this))))))
  (render [this] (dom/div #js {:key "placeholder" :ref (fn [r] (set! (.-doc-element this) r))})))

(def ui-render-in-body (om/factory RenderInBody {:keyfn :key}))

(defui ^:once PopOver
  Object
  (componentWillUpdate [this new-props ns]
    (let [old-props        (om/props this)
          becoming-active? (and (:active new-props) (not (:active old-props)))]
      (when becoming-active? (om/update-state! this update :render-for-size inc))))
  (render [this]
    (let [{:keys [active orientation] :or {orientation :top}} (om/props this)
          target     (.-target-ref this)
          popup      (.-popup-ref this)
          popup-box  (if popup (get-abs-position popup) {})
          target-box (if target (get-abs-position target) {})
          deltaY     (case orientation
                       :bottom (:height target-box)
                       :left (-> (:height target-box)
                               (- (:height popup-box))
                               (/ 2))
                       :right (-> (:height target-box)
                                (- (:height popup-box))
                                (/ 2))
                       (- (:height popup-box)))
          deltaX     (case orientation
                       :left (- (:width popup-box))
                       :right (:width target-box)
                       (/ (- (:width target-box) (:width popup-box)) 2))
          popupTop   (if active (+ (:top target-box) deltaY) -1000)
          popupLeft  (if active (+ (:left target-box) deltaX) -1000)]
      (dom/span #js {:style #js {:display "inline-block"} :ref (fn [r] (set! (.-target-ref this) r))}
        (ui-render-in-body {}
          (dom/div #js {:className (str "popover fade " (name orientation) (when active " in"))
                        :ref       (fn [r] (set! (.-popup-ref this) r))
                        :style     #js {:position "absolute"
                                        :top      (str popupTop "px")
                                        :left     (str popupLeft "px")
                                        :display  "block"}}
            (dom/div #js {:className "arrow" :style #js {:left (case orientation
                                                                 :left "100%"
                                                                 :right "-11px"
                                                                 "50%")}})
            (dom/h3 #js {:className "popover-title"} "Title")
            (dom/div #js {:className "popover-content"} "This is a test of a popover")))
        (om/children this)))))

(def ui-popover (om/factory PopOver))

(defn modal-ident
  "Get the ident for a modal with the given props or ID"
  [props-or-id]
  (if (map? props-or-id)
    [:untangled.ui.boostrap3.modal/by-id (:db/id props-or-id)]
    [:untangled.ui.boostrap3.modal/by-id props-or-id]))

(defn- show-modal* [modal tf]
  (assoc modal :modal/visible tf))

(defn- activate-modal* [modal tf]
  (assoc modal :modal/active tf))

#?(:cljs
   (defmutation show-modal
     "Om Mutation: Show a modal by ID."
     [{:keys [id]}]
     (action [{:keys [state]}]
       (swap! state update-in (modal-ident id) show-modal* true)
       (js/setTimeout (fn [] (swap! state update-in (modal-ident id) activate-modal* true)) 10))))

#?(:cljs
   (defmutation hide-modal
     "Om mutation: Hide a modal by ID."
     [{:keys [id]}]
     (action [{:keys [state]}]
       (swap! state update-in (modal-ident id) activate-modal* false)
       (js/setTimeout (fn [] (swap! state update-in (modal-ident id) show-modal* false)) 300))))

(defui ^:once ModalTitle
  Object
  (render [this]
    (apply dom/div (clj->js (om/props this)) (om/children this))))

(def ui-modal-title (om/factory ModalTitle {:keyfn (fn [props] "modal-title")}))

(defui ^:once ModalBody
  Object
  (render [this]
    (div-with-class "modal-body" (om/props this) (om/children this))))

(def ui-modal-body (om/factory ModalBody {:keyfn (fn [props] "modal-body")}))

(defui ^:once ModalFooter
  Object
  (render [this]
    (div-with-class "modal-footer" (om/props this) (om/children this))))

(def ui-modal-footer (om/factory ModalFooter {:keyfn (fn [props] "modal-footer")}))

(defui ^:once Modal
  static uc/InitialAppState
  (initial-state [c {:keys [id sz backdrop keyboard] :or {backdrop true keyboard true}}]
    {:db/id      id :modal/active false :modal/visible false :modal/keyboard keyboard
     :modal/size sz :modal/backdrop (boolean backdrop)})
  static om/Ident
  (ident [this props] (modal-ident props))
  static om/IQuery
  (query [this] [:db/id :modal/active :modal/visible :modal/size :modal/backdrop :modal/keyboard])
  Object
  (componentDidMount [this] (.focus (.-the-dialog this)))
  (componentDidUpdate [this pp _] (when (and (:modal/visible (om/props this)) (not (:modal/visible pp))) (.focus (.-the-dialog this))))
  (render [this]
    (let [{:keys [db/id modal/active modal/visible modal/size modal/keyboard modal/backdrop]} (om/props this)
          {:keys [onClose]} (om/get-computed this)
          onClose  (fn []
                     (om/transact! this `[(hide-modal {:id ~id})])
                     (when onClose (onClose id)))
          children (om/children this)
          label-id (str "modal-label-" id)
          title    (util/first-node ModalTitle children)
          body     (util/first-node ModalBody children)
          footer   (util/first-node ModalFooter children)]
      (dom/div #js {:role      "dialog" :aria-labelledby label-id
                    :ref       (fn [r] (set! (.-the-dialog this) r))
                    :onKeyDown (fn [evt] (when (and keyboard (evt/escape-key? evt)) (onClose)))
                    :style     #js {:display (if visible "block" "none")}
                    :className (str "modal fade" (when active " in")) :tabIndex "-1"}
        (dom/div #js {:role "document" :className (str "modal-dialog" (when size (str " modal-" (name size))))}
          (dom/div #js {:className "modal-content"}
            (dom/div #js {:key "modal-header" :className "modal-header"}
              (dom/button #js {:type "button" :onClick onClose :aria-label "Close" :className "close"}
                (dom/span #js {:aria-hidden "true"} ent/times))
              (when title
                (dom/h4 #js {:key label-id :id label-id :className "modal-title"} title)))
            (when body body)
            (when footer footer)))
        (when (and backdrop visible)
          (ui-render-in-body {}
            (dom/div #js {:key "backdrop" :className (str "modal-backdrop fade" (when active " in"))})))))))

(let [modal-factory (om/factory Modal {:keyfn (fn [props] (str "modal-" (:db/id props)))})]
  (defn ui-modal
    "Render a modal.

    Modals are stateful. You must compose in initial state and a query. Modals also have IDs.

    Modal content should include a ui-modal-title, ui-modal-body, and ui-modal-footer as children. The footer usually contains
    one or more buttons.

    Use the `uc/get-initial-state` function to pull a valid initial state for this component. The arguments are:

    `(uc/get-initial-state Modal {:id ID :sz SZ :backdrop BOOLEAN})`

    where the id is required (and must be unique among modals, and `:sz` is optional and must be `:sm` or `:lg`. The
    :backdrop option is boolean, and indicates you want a backdrop that blocks the UI. The `:keyboard` option
    defaults to true and enables removal of the dialog with `ESC`.

    When rendering the modal, it typically looks something like this:

    ````
    (b/ui-modal modal
      (b/ui-modal-title nil
        (dom/b nil \"WARNING!\"))
      (b/ui-modal-body nil
        (dom/p #js {:className b/text-danger} \"Stuff went sideways.\"))
      (b/ui-modal-footer nil
        (b/button {:onClick #(om/transact! this `[(b/hide-modal {:id :warning-modal})])} \"Bummer!\"))))))
    ````

    NOTE: The grid (`row` and `col`) can be used within the modal body *without* a `container`.

    See the developer's guide for an example in the N15-Twitter-Bootstrap-Components section.

    Available mudations: `b/show-modal` and `b/hide-modal`."
    [props & children]
    (apply modal-factory props children)))

(defn collapse-ident [id-or-props]
  (if (map? id-or-props)
    [:untangled.ui.bootstrap3.collapse/by-id (:db/id id-or-props)]
    [:untangled.ui.bootstrap3.collapse/by-id id-or-props]))

(defui Collapse
  static uc/InitialAppState
  (initial-state [c {:keys [id start-open]}] {:db/id id :collapse/phase :closed})
  static om/Ident
  (ident [this props] (collapse-ident props))
  static om/IQuery
  (query [this] [:db/id :collapse/phase])
  Object
  (render [this]
    (let [{:keys [db/id collapse/phase]} (om/props this)
          dom-ele    (.-dom-element this)
          box-height (when dom-ele (.-height (.getBoundingClientRect dom-ele)))
          height     (when dom-ele
                       (case phase
                         :opening-no-height nil
                         :opening (str (.-scrollHeight dom-ele) "px")
                         :open nil
                         :closing (str box-height "px")
                         "0px"))
          children   (om/children this)
          classes    (case phase
                       :open "collapse in"
                       :closed "collapse"
                       "collapsing")]
      (apply dom/div #js {:className classes :style #js {:height height} :ref (fn [r] (set! (.-dom-element this) r))} children))))

(def ui-collapse
  "Render a collapse component that can height-animate in/out children. The props should be state from the
  app database initialized with `get-initial-state` of a Collapse component,
  and the children should be the elements you want to show/hide. Each component should have a unique
  (application-wide) ID. Use the `toggle-collapse` and `set-collapse` mutations to open/close. "
  (om/factory Collapse {:keyfn :db/id}))

(defn- is-stable?
  "Returns true if the given collapse item is not transitioning"
  [collapse-item]
  (#{:open :closed} (:collapse/phase collapse-item)))

(defn- set-collapse*
  "state is a state atom"
  [state id open]
  ; phases [:opening-no-height :opening :open :closing :closed]
  (let [cident (collapse-ident id)
        item   (get-in @state cident)
        ppath  (conj cident :collapse/phase)]
    (when (is-stable? item)
      (if open
        (do
          (swap! state assoc-in ppath :opening-no-height)
          #?(:cljs (js/setTimeout (fn [] (swap! state assoc-in ppath :opening)) 16))
          #?(:cljs (js/setTimeout (fn [] (swap! state assoc-in ppath :open)) 350)))
        (do
          (swap! state assoc-in ppath :closing)
          #?(:cljs (js/setTimeout (fn [] (swap! state assoc-in ppath :close-height)) 16))
          #?(:cljs (js/setTimeout (fn [] (swap! state assoc-in ppath :closed)) 350)))))))

(defmutation toggle-collapse
  "Om mutation: Toggle a collapse"
  [{:keys [id]}]
  (action [{:keys [state]}]
    (let [cident  (collapse-ident id)
          ppath   (conj cident :collapse/phase)
          is-open (= :open (get-in @state ppath))]
      (set-collapse* state id (not is-open)))))

(defmutation set-collapse
  "Om mutation: Set the state of a collapse."
  [{:keys [id open]}]
  (action [{:keys [state]}]
    (set-collapse* state id open)))

(defmutation toggle-collapse-group-item
  "Om mutation: Toggle a collapse element as if in a group.

  item-id: The specific group to toggle
  all-item-ids: A collection of all of the items that are to be considered part of the group. If any items are
  in transition, this is a no-op."
  [{:keys [item-id all-item-ids]}]
  (action [{:keys [state]}]
    (let [all-ids        (set all-item-ids)
          all-items      (map #(get-in @state (collapse-ident %)) all-ids)
          item-to-toggle (get-in @state (collapse-ident item-id))
          closing?       (= :open (:collapse/phase item-to-toggle))
          stable?        (every? is-stable? all-items)]
      (when stable?
        (if closing?
          (set-collapse* state item-id false)
          (let [open?        (fn [item] (= :open (:collapse/phase item)))
                ids-to-close (->> all-items
                               (filter open?)
                               (map :db/id))]
            (doseq [id ids-to-close]
              (set-collapse* state id false))
            (set-collapse* state item-id true)))))))


(defn carousel-ident [props-or-id]
  (if (map? props-or-id)
    [:untangled.ui.bootstrap3.carousel/by-id (:db/id props-or-id)]
    [:untangled.ui.bootstrap3.carousel/by-id props-or-id]))

(defui CarouselItem
  Object
  (render [this]
    (let [{:keys [src alt] :as props} (om/props this)
          caption (om/children this)]
      (dom/div #js {:key (hash src)}
        (dom/img #js {:src src :alt alt})
        (when (seq caption)
          (dom/div #js {:className "carousel-caption"}
            caption))))))

(def ui-carousel-item
  "Render a carousel item. Props can include src and alt for the image. If children are supplied, they will be
  treated as the caption."
  (om/factory CarouselItem {:keyfn :index}))


(defmutation carousel-slide-to
  "Om mutation: Slides a carousel from the current frame to the indicated frame. The special `frame` value of `:wrap`
  can be used to slide from the current frame to the first as if wrapping in a circle."
  [{:keys [id frame]}]
  (action [{:keys [state]}]
    (let [cident       (carousel-ident id)
          carousel     (get-in @state cident)
          {:keys [carousel/timer-id carousel/paused carousel/active-index
                  carousel/slide-to carousel/interval]} carousel
          new-timer-id (when (not slide-to)
                         #?(:cljs (js/setTimeout (fn []
                                                   (swap! state update-in (carousel-ident id)
                                                     assoc
                                                     :carousel/timer-id nil
                                                     :carousel/active-index frame
                                                     :carousel/slide-to nil)) 600)))]
      (when (not slide-to)
        #?(:cljs (when timer-id
                   (js/clearTimeout timer-id)))
        (swap! state assoc :carousel/slide-to frame :carousel/timer-id new-timer-id)))))

(defui Carousel
  static uc/InitialAppState
  (initial-state [c {:keys [id interval wrap keyboard pause-on-hover show-controls]
                     :or   {interval 5000 wrap true keyboard true pause-on-hover true show-controls true}}]
    {:db/id                   id
     :carousel/interval       interval
     :carousel/active-index   0
     :carousel/show-controls  show-controls
     :carousel/wrap           wrap
     :carousel/keyboard       keyboard
     :carousel/pause-on-hover pause-on-hover
     :carousel/paused         false
     :carousel/timer-id       nil})
  static om/Ident
  (ident [this props] (carousel-ident props))
  static om/IQuery
  (query [this] [:db/id :carousel/active-index :carousel/slide-to :carousel/show-controls])
  Object
  (render [this]
    (let [items             (om/children this)
          slide-count       (count items)
          {:keys [db/id carousel/active-index carousel/slide-to carousel/show-controls]} (om/props this)
          to                (if (= :wrap slide-to) 0 slide-to)
          sliding?          (and slide-to (not= active-index slide-to))
          prior-index       (if (zero? active-index) (dec slide-count) (dec active-index))
          next-index        (if (= (dec slide-count) active-index) 0 (inc active-index))
          goto              (fn [slide] (om/transact! this `[(carousel-slide-to {:id ~id :frame ~slide})]))
          from-the-left?    (or (= :wrap slide-to) (< active-index slide-to))
          active-item-class (str "item active "
                              (when sliding? (if from-the-left? "left" "right")))
          slide-to-class    (str "item " (when sliding? (if from-the-left? "next left" "next right")))]
      (dom/div #js {:className "carousel slide"
                    :onKeyDown (fn [e]
                                 (.preventDefault e) ; TODO: not getting key evts
                                 (.stopPropagation e)
                                 (log/info (.-keyCode e))
                                 (cond
                                   (evt/left-arrow? e) (goto prior-index)
                                   (evt/right-arrow? e) (goto next-index))
                                 false)}
        (dom/ol #js {:className "carousel-indicators"}
          (map #(dom/li #js {:key % :className (str "" (when (= % active-index) "active"))} "") (range slide-count)))

        ; TODO: extra div needs unwrapped, but is already rendered
        (dom/div #js {:className "carousel-inner" :role "listbox"}
          (map-indexed (fn [idx i]
                         (dom/div #js {:className (cond
                                                    (= idx to) slide-to-class
                                                    (= idx active-index) active-item-class
                                                    :else "")} i))
            items))
        (when show-controls
          (dom/a #js {:onClick #(goto prior-index) :className "left carousel-control" :role "button"}
            (glyphicon {:aria-hidden true} :chevron-left)
            (dom/span #js {:className "sr-only"} "Previous"))
          (dom/a #js {:onClick #(goto next-index) :className "right carousel-control" :role "button"}
            (glyphicon {:aria-hidden true} :chevron-right)
            (dom/span #js {:className "sr-only"} "Next")))))))
; TODO: above is untested...might work ;)

(def ui-carousel (om/factory Carousel {:keyfn :db/id}))

;; TODO: Carousel (stateful)
;; TODO: Scrollspy (spy-link component that triggers mutation + scrollspy component that gets updated on those mutations)
;; TODO: Affix (similar to scrollspy in terms of interactions)
;; TODO: Media Object
;; TODO: List Group with table etc integrations

;; TODO: Form integration: auto-rendering an Untangled Form...also just form field renderers as extensions to form-field multimethod.