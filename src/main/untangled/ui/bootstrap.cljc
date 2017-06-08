(ns untangled.ui.bootstrap
  (:require [om.dom :as dom]
            [om.next :as om :refer [defui]]
    #?(:clj
            js)
            [untangled.ui.elements :as ele]
            [untangled.i18n :refer [tr tr-unsafe]]
            [untangled.client.mutations :as m]
            [clojure.string :as str]
            [clojure.set :as set]
            [untangled.client.core :as uc]
            [untangled.client.logging :as log]))

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
                           (not size) (str " btn-default")
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dropdowns
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def dropdown-table :bootstrap.dropdown/by-id)
(def dropdown-item-table :bootstrap.dropdown-item/by-id)

(defn dropdown-item
  "Define the state for an item of a dropdown. Optional named parameter :select-tx is an Om tx to run (in the context of the
  dropdown) when selected. You may also use the onSelect callback to find out when an item is selected."
  [id label & {:keys [select-tx]}] {::id id ::label label ::tx select-tx})
(defn dropdown-divider
  "Creates a divider between items. Must have a unique ID"
  [id] {::id id ::label ::divider})
(defn dropdown
  "Creates a dropdown's state. Create items with dropdown-item or dropdown-divider."
  [id label items] {::id id ::label label ::items items ::open? false :type dropdown-table})

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
  "Om Mutation. Set the active flag to true/false on a specific dropdown item, by id."
  [{:keys [id active?]}]
  (action [{:keys [state]}]
    (let [kpath (conj (dropdown-item-ident id) ::active?)]
      (swap! state assoc-in kpath active?))))

(defn- close-all-dropdowns-impl [dropdown-map]
  (reduce (fn [m id] (assoc-in m [id ::open?] false)) dropdown-map (keys dropdown-map)))

(m/defmutation close-all-dropdowns
  "Om Mutations: Close all dropdowns (globally)"
  [ignored]
  (action [{:keys [state]}]
    (swap! state update dropdown-table close-all-dropdowns-impl)))

(defui ^:once DropdownItem
  static om/IQuery
  (query [this] [::id ::label ::tx ::active? ::disabled? :type])
  static om/Ident
  (ident [this props] (dropdown-item-ident props))
  Object
  (render [this]
    (let [{:keys [::label ::id ::tx ::active? ::disabled?] :or {tx []}} (om/props this)
          active?  (or active? (om/get-computed this :active?))
          onSelect (or (om/get-computed this :onSelect) identity)]
      (if (= ::divider label)
        (dom/li #js {:key id :role "separator" :className "divider"})
        (dom/li #js {:key id :className (if disabled? "disabled" "")}
          (dom/a #js {:onClick (fn [evt]
                                 (.stopPropagation evt)
                                 (onSelect id tx)
                                 false)} (tr-unsafe label)))))))

(let [ui-dropdown-item-factory (om/factory DropdownItem {:keyfn ::id})]
  (defn ui-dropdown-item
    "Render a dropdown item. The props are the state props of the dropdown item. The additional by-name
    arguments:

    onSelect - The function to call when a menu item is selected
    "
    [props & {:keys [onSelect]}]
    (ui-dropdown-item-factory (om/computed props {:onSelect onSelect}))))

(defui ^:once Dropdown
  static om/IQuery
  (query [this] [::id ::label ::open? {::items (om/get-query DropdownItem)} :type])
  static om/Ident
  (ident [this props] (dropdown-ident props))
  Object
  (render [this]
    (let [{:keys [::id ::label ::items ::open?]} (om/props this)
          {:keys [onSelect kind]} (om/get-computed this)
          active?   (some ::active? items)
          onSelect  (fn [id tx]
                      (let [tx (if tx tx [])]
                        (om/transact! this (into `[(close-all-dropdowns {})] tx)))
                      (when onSelect (onSelect id)))
          open-menu (fn [evt]
                      (.stopPropagation evt)
                      (om/transact! this `[(close-all-dropdowns {}) (set-dropdown-open ~{:id id :open? (not open?)})])
                      false)]
      (button-group {:className (if open? "open" "")}
        (button {:className (str "dropdown-toggle"
                              (when kind (str " btn-" (name kind)))
                              (when active? " active")) :aria-haspopup true :aria-expanded open? :onClick open-menu}
          (tr-unsafe label) " " (dom/span #js {:className "caret"}))
        (dom/ul #js {:className "dropdown-menu"}
          (map #(ui-dropdown-item % :onSelect onSelect) items))))))

(let [ui-dropdown-factory (om/factory Dropdown {:keyfn ::id})]
  (defn ui-dropdown
    "Render a dropdown. The props are the state props of the dropdown. The additional by-name
    arguments:

    onSelect - The function to call when a menu item is selected
    kind - The kind of dropdown. See `button`."
    [props & {:keys [onSelect kind]}]
    (ui-dropdown-factory (om/computed props {:onSelect onSelect :kind kind}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; NAV (tabs/pills)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def nav-table :bootstrap.nav/by-id)
(def nav-link-table :bootstrap.navitem/by-id)

(defn nav-link
  "Creates a navigation link. ID must be globally unique. The label will be run through `tr-unsafe`, so it can be
  internationalized. The `app-nav-tx` is an Om transaction to run when this link is selected."
  [id label disabled? select-tx]
  {::id id ::label label ::disbled? disabled? ::tx select-tx :type nav-link-table})

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
  (query [this] [::id ::label ::disabled? ::tx :type])
  static om/Ident
  (ident [this props] (nav-link-ident props))
  Object
  (render [this]
    (let [{:keys [::id ::label ::disabled? ::tx]} (om/props this)
          active? (om/get-computed this :active?)]
      (dom/li #js {:role "presentation" :className (cond-> ""
                                                     active? (str " active")
                                                     disabled? (str " disabled"))}
        (dom/a #js {:onClick #(om/transact! this tx)} (tr-unsafe label))))))

(def ui-nav-link (om/factory NavLink {:keyfn ::id}))

(defui ^:once NavItemUnion
  static om/Ident
  (ident [this {:keys [::id type]}] [type id])
  static om/IQuery
  (query [this] {dropdown-table (om/get-query Dropdown) nav-link-table (om/get-query NavLink)})
  Object
  (render [this]
    (let [{:keys [type] :as child} (om/props this)
          computed (om/get-computed this)]
      (case type
        :bootstrap.navitem/by-id (ui-nav-link (om/computed child computed))
        :bootstrap.dropdown/by-id (ui-dropdown (om/computed child computed))
        (dom/p nil "Unknown link type!")))))

(def ui-nav-item (om/factory NavItemUnion {:keyfn ::id}))

(defui ^:once Nav
  static om/Ident
  (ident [this props] (nav-ident props))
  static om/IQuery
  (query [this] [::id ::kind ::layout ::active-link-id {::links (om/get-query NavItemUnion)}])
  Object
  (render [this]
    (let [{:keys [::id ::kind ::layout ::active-link-id ::links]} (om/props this)]
      (dom/ul #js {:className (str "nav nav-" (name kind) (case layout :justified " nav-justified" :stacked " nav-stacked" ""))}
        (map #(ui-nav-item (om/computed % {:active? (= (::id %) active-link-id)})) links)))))

(def ui-nav (om/factory Nav {:keyfn ::id}))
