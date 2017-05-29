(ns untangled-devguide.N-Twitter-Bootstrap
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc deftest]]
            [cljs.reader :as r]
            [om.next.impl.parser :as p]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [untangled.ui.elements :as ele]
            [untangled.client.cards :refer [untangled-app]]
            [untangled.ui.bootstrap :as b]
            [untangled.client.core :as uc]))

(declare render-example sample)

(defcard-doc
  "
  # Twitter Bootstrap

  Untangled includes functions that emit the DOM with CSS for version 3 of Twitter's Bootstrap CSS and Components.

  Notice that the CSS affects many DOM elements, which means you'll see examples that use Om's DOM functions, which
  in turn require a JavaScript object as the first argument (for performance). The helper functions from the
  `bootstrap` namespace need to modify the incoming arguments, so the first argument (if it takes DOM props) is
  a cljs map instead. ")

(defn- col [attrs children] (b/col (assoc attrs :className "boxed") children))

(defcard grids
  "
  The four iframes below represent the widths of a large, medium, small, and xsmall screen. The content being
  rendered is:

  ```
  (b/container-fluid {}
    (b/row {}
      (b/col {:xs 12 :md 8} \"xs 12 md 8\") (b/col {:xs 6 :md 4} \"xs 6 md 4\"))
    (b/row {}
      (b/col {:xs 6 :md 4} \"xs 6 md 4\")
      (b/col {:xs 6 :md 4} \"xs 6 md 4\")
      (b/col {:xs 6 :md 4} \"xs 6 md 4\"))
    (b/row {} (b/col {:xs 6 } \"xs 6\") (b/col {:xs 6 } \"xs 6\")))
  ```

  See the Bootstrap documetation for more details.
  "
  (fn [state _]
    (dom/div nil
      (dom/h4 nil "Large")
      (render-example "1400px" "100px"
        (b/container-fluid {}
          (b/row {}
            (col {:xs 12 :md 8} "xs 12 md 8") (col {:xs 6 :md 4} "xs 6 md 4"))
          (b/row {}
            (col {:xs 6 :md 4} "xs 6 md 4")
            (col {:xs 6 :md 4} "xs 6 md 4")
            (col {:xs 6 :md 4} "xs 6 md 4"))
          (b/row {} (col {:xs 6} "xs 6") (col {:xs 6} "xs 6"))))
      (dom/h4 nil "Medium")
      (render-example "1000px" "100px"
        (b/container-fluid {}
          (b/row {}
            (col {:xs 12 :md 8} "xs 12 md 8") (col {:xs 6 :md 4} "xs 6 md 4"))
          (b/row {}
            (col {:xs 6 :md 4} "xs 6 md 4")
            (col {:xs 6 :md 4} "xs 6 md 4")
            (col {:xs 6 :md 4} "xs 6 md 4"))
          (b/row {} (col {:xs 6} "xs 6") (col {:xs 6} "xs 6"))))
      (dom/h4 nil "Small")
      (render-example "800px" "120px"
        (b/container-fluid {}
          (b/row {}
            (col {:xs 12 :md 8} "xs 12 md 8") (col {:xs 6 :md 4} "xs 6 md 4"))
          (b/row {}
            (col {:xs 6 :md 4} "xs 6 md 4")
            (col {:xs 6 :md 4} "xs 6 md 4")
            (col {:xs 6 :md 4} "xs 6 md 4"))
          (b/row {} (col {:xs 6} "xs 6") (col {:xs 6} "xs 6"))))
      (dom/h4 nil "X-Small")
      (render-example "600px" "120px"
        (b/container-fluid {}
          (b/row {}
            (col {:xs 12 :md 8} "xs 12 md 8") (col {:xs 6 :md 4} "xs 6 md 4"))
          (b/row {}
            (col {:xs 6 :md 4} "xs 6 md 4")
            (col {:xs 6 :md 4} "xs 6 md 4")
            (col {:xs 6 :md 4} "xs 6 md 4"))
          (b/row {} (col {:xs 6} "xs 6") (col {:xs 6} "xs 6")))))))

(defcard typography
  "Various elements support modified typography."
  (render-example "100%" "400px"
    (b/container {}
      (b/row nil
        (b/col {:xs 6} "(b/lead {} \"Lead Text\")")
        (b/col {:xs 6} (b/lead {} "Lead Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/p #js {} \"Paragraph Text\")")
        (b/col {:xs 6} (dom/p #js {} "Paragraph Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/mark #js {} \"Highlighted Text\")")
        (b/col {:xs 6} (dom/mark #js {} "Highlighted Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/del #js {} \"Deleted Text\")")
        (b/col {:xs 6} (dom/del #js {} "Deleted Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/s #js {} \"Strikethrough Text\")")
        (b/col {:xs 6} (dom/s #js {} "Strikethrough Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/ins #js {} \"Inserted Text\")")
        (b/col {:xs 6} (dom/ins #js {} "Inserted Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/u #js {} \"Underlined Text\")")
        (b/col {:xs 6} (dom/u #js {} "Underlined Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/small #js {} \"Small Text\")")
        (b/col {:xs 6} (dom/small #js {} "Small Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/strong #js {} \"Bold Text\")")
        (b/col {:xs 6} (dom/strong #js {} "Bold Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/em #js {} \"Italic Text\")")
        (b/col {:xs 6} (dom/em #js {} "Italic Text")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(b/address \"Some Company\" :street \"111 NW 1st St.\" :city-state \"Chicago, IL 43156\" :phone \"(316) 555-1212\")")
        (b/col {:xs 6} (b/address "Some Company" :street "111 NW 1st St." :city-state "Chicago, IL 43156"
                         :phone "(316) 555-1212")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/blockquote nil (dom/p nil \"Block quoted Text\"))")
        (b/col {:xs 6} (dom/blockquote nil (dom/p nil "Block quoted Text"))))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(b/quotation {:source \"in Joe's Diary\" :credit \"Joe\"} (dom/p nil \"Some crap he said.\"))")
        (b/col {:xs 6} (b/quotation {:source "in Joe's Diary" :credit "Joe"} (dom/p nil "Some crap he said."))))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(b/quotation {:align :right :source \"in Joe's Diary\" :credit \"Joe\"} (dom/p nil \"Some crap he said.\"))")
        (b/col {:xs 6} (b/quotation {:align :right :source "in Joe's Diary" :credit "Joe"} (dom/p nil "Some crap he said."))))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/ul #js {} (dom/li nil \"A\") (dom/li nil \"B\")")
        (b/col {:xs 6} (dom/ul #js {} (dom/li nil "A") (dom/li nil "B"))))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(b/plain-ul {} (dom/li nil \"A\") (dom/li nil \"B\")")
        (b/col {:xs 6} (b/plain-ul {} (dom/li nil "A") (dom/li nil "B"))))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(b/inline-ul {} (dom/li nil \"A\") (dom/li nil \"B\")")
        (b/col {:xs 6} (b/inline-ul {} (dom/li nil "A") (dom/li nil "B")))))))


(defcard formatting-code
  "The look of formatted code."
  (render-example "100%" "400px"
    (b/container {}
      (b/row nil
        (b/col {:xs 6} "(dom/code #js {} \"pwd\")")
        (b/col {:xs 6} (dom/code #js {} "pwd")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/kbd #js {} \"CTRL-C\")")
        (b/col {:xs 6} (dom/kbd #js {} "CTRL-C")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/pre #js {} \"(map identity\\n  list)\")")
        (b/col {:xs 6} (dom/pre #js {} "(map identity\n  list)")))
      (dom/hr nil)
      (b/row nil
        (b/col {:xs 6} "(dom/samp #js {} \"This is supposed to represent computer output\")")
        (b/col {:xs 6} (dom/samp #js {} "This is supposed to represent computer output")))
      (dom/hr nil))))

(defcard tables
  "Bootstrip includes styles for tables. The `b/table` wrapper handles remember the classes for you."
  (render-example "100%" "400px"
    (dom/div nil
      (dom/h4 nil "A plain table (table class automatically added with `(b/table ...)`:")
      (b/table {}
        (dom/thead nil
          (dom/tr nil
            (dom/th nil "H1")
            (dom/th nil "H2")
            (dom/th nil "H3")))
        (dom/tbody nil
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3"))
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3"))
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3"))))
      (dom/h4 nil "A table with `(b/table { :styles #{:striped :hover} } ...)`:")
      (b/table {:styles #{:striped :hover}}
        (dom/thead nil
          (dom/tr nil
            (dom/th nil "H1")
            (dom/th nil "H2")
            (dom/th nil "H3")))
        (dom/tbody nil
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3"))
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3"))
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3"))))
      (dom/h4 nil "A table with `(b/table { :styles #{:bordered :condensed} } ...)`:")
      (b/table {:styles #{:bordered :condensed}}
        (dom/thead nil
          (dom/tr nil
            (dom/th nil "H1")
            (dom/th nil "H2")
            (dom/th nil "H3")))
        (dom/tbody nil
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3"))
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3"))
          (dom/tr nil
            (dom/td nil "1")
            (dom/td nil "2")
            (dom/td nil "3")))))))

(defcard form-fields
  "Labelled fields for forms"
  (render-example "100%" "400px"
    (dom/div #js {}
      (b/labeled-input {:id "name" :type "text"} "Name:")
      (b/labeled-input {:id "address" :type "text" :error "Must not be empty!"} "Address:")
      (b/labeled-input {:id "phone" :type "text" :success "You can leave this blank."} "Phone:")
      (b/labeled-input {:id "email" :type "email" :help "Your primary email"} "Email:"))))

(defcard form-fields
  "Labelled fields for forms"
  (render-example "100%" "400px"
    (dom/div #js {:className "form-horizontal"}
      (b/labeled-input {:id "name" :split 2 :type "text"} "Name:")
      (b/labeled-input {:id "address" :split 2 :type "text" :error "Must not be empty!"} "Address:")
      (b/labeled-input {:id "phone" :split 2 :type "text"} "Phone:")
      (b/labeled-input {:id "email" :split 2 :type "email" :help "Your primary email"} "Email:"))))

(defcard buttons
  "Various buttons"
  (render-example "100%" "130px"
    (apply dom/div nil
      (dom/div #js {}
        "A close button: " (b/close-button {:style #js {:float "none"}}))
      (b/button {:key "A"} "Default")
      (b/button {:key "b" :size :xs} "Default xs")
      (b/button {:key "c" :size :sm} "Default sm")
      (b/button {:key "d" :size :lg} "Default lg")
      (for [k [:primary :success :info :warning :danger]
            s [:xs :sm :lg]]
        (b/button {:key (str (name k) (name s)) :kind k :size s} (str (name k) " " (name s)))))))

(def svg-placeholder "data:image/svg+xml;base64,PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0iVVRGLTgiIHN0YW5kYWxvbmU9InllcyI/PjxzdmcgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIiB3aWR0aD0iMTQwIiBoZWlnaHQ9IjE0MCIgdmlld0JveD0iMCAwIDE0MCAxNDAiIHByZXNlcnZlQXNwZWN0UmF0aW89Im5vbmUiPjwhLS0KU291cmNlIFVSTDogaG9sZGVyLmpzLzE0MHgxNDAKQ3JlYXRlZCB3aXRoIEhvbGRlci5qcyAyLjYuMC4KTGVhcm4gbW9yZSBhdCBodHRwOi8vaG9sZGVyanMuY29tCihjKSAyMDEyLTIwMTUgSXZhbiBNYWxvcGluc2t5IC0gaHR0cDovL2ltc2t5LmNvCi0tPjxkZWZzPjxzdHlsZSB0eXBlPSJ0ZXh0L2NzcyI+PCFbQ0RBVEFbI2hvbGRlcl8xNWM0N2NlZGU5NCB0ZXh0IHsgZmlsbDojQUFBQUFBO2ZvbnQtd2VpZ2h0OmJvbGQ7Zm9udC1mYW1pbHk6QXJpYWwsIEhlbHZldGljYSwgT3BlbiBTYW5zLCBzYW5zLXNlcmlmLCBtb25vc3BhY2U7Zm9udC1zaXplOjEwcHQgfSBdXT48L3N0eWxlPjwvZGVmcz48ZyBpZD0iaG9sZGVyXzE1YzQ3Y2VkZTk0Ij48cmVjdCB3aWR0aD0iMTQwIiBoZWlnaHQ9IjE0MCIgZmlsbD0iI0VFRUVFRSIvPjxnPjx0ZXh0IHg9IjQ0LjA1NDY4NzUiIHk9Ijc0LjUiPjE0MHgxNDA8L3RleHQ+PC9nPjwvZz48L3N2Zz4=")

(defcard images
  "Various images"
  (render-example "100%" "250px"
    (b/container-fluid {}
      (b/row {}
        (b/col {:sm 3}
          (sample (b/img {:src svg-placeholder}) "Regular"))
        (b/col {:sm 3}
          (sample (b/img {:src svg-placeholder :shape :rounded}) "Rounded"))
        (b/col {:sm 3}
          (sample (b/img {:src svg-placeholder :shape :circle}) "Circle"))
        (b/col {:sm 3}
          (sample (b/img {:src svg-placeholder :shape :thumbnail}) "Thumbnail"))))))

(defcard icons
  "Glyphicon support through keyword names and the b/glyphicon function. E.g. `(b/glyphicon {} :arrow-left)`."
  (render-example "100%" "250px"
    (let [rows (map #(apply b/row {:className b/text-center :style #js {:marginBottom "5px"}} %)
                 (partition 6 (for [i (sort b/glyph-icons)]
                                (b/col {:xs 2}
                                  (dom/div #js {:style #js {:border "1px solid black" :padding "2px" :wordWrap "break-word"}}
                                    (b/glyphicon {:size "12pt"} i) (dom/br nil) (str i))))))]
      (apply b/container {}
        rows))))

(defcard button-groups
  "Some button group examples:"
  (render-example "100%" "500px"
    (b/container-fluid {}
      (dom/h4 nil "Regular")
      (b/button-group {} (b/button {} "A") (b/button {} "B") (b/button {} "C"))
      (dom/br nil)
      (dom/h4 nil "xs")
      (b/button-group {:size :xs} (b/button {} "A") (b/button {} "B") (b/button {} "C"))
      (dom/br nil)
      (dom/h4 nil "sm")
      (b/button-group {:size :sm} (b/button {} "A") (b/button {} "B") (b/button {} "C"))
      (dom/br nil)
      (dom/h4 nil "lg")
      (b/button-group {:size :lg} (b/button {} "A") (b/button {} "B") (b/button {} "C"))
      (dom/h4 nil "vertical")
      (b/button-group {:kind :vertical} (b/button {} "A") (b/button {} "B") (b/button {} "C"))
      (dom/h4 nil "justified")
      (b/button-group {:kind :justified} (b/button {} "A") (b/button {} "B") (b/button {} "C")))))

(defcard button-toolbar
  "A button toolbar puts groups together"
  (render-example "100%" "75px"
    (b/container-fluid {}
      (dom/h4 nil "")
      (b/button-toolbar {}
        (b/button-group {} (b/button {} "A") (b/button {} "B") (b/button {} "C"))
        (b/button-group {} (b/button {} "D") (b/button {} "E"))))))

(defui DropdownRoot
  static uc/InitialAppState
  (initial-state [this props] {:dropdown (b/dropdown :file "File" [(b/dropdown-item :open "Open")
                                                                   (b/dropdown-item :close "Close")
                                                                   (b/dropdown-divider)
                                                                   (b/dropdown-item :quit "Quit")])})
  static om/IQuery
  (query [this] [{:dropdown (om/get-query b/Dropdown)}])
  Object
  (render [this]
    (let [{:keys [dropdown]} (om/props this)]
      (render-example "100%" "150px"
        (let [select (fn [id] (js/alert (str "Selected: " id)))]
          (dom/div #js {:height "100%" :onClick #(om/transact! this `[(b/close-all-dropdowns {})])}
            (b/ui-dropdown dropdown :onSelect select :kind :success)))))))

(defcard-doc
  "
  # Active Dropdown Component

  Active dropdowns are Om components with state.

  - `dropdown` - a function that creates a dropdown's state
  - `dropdown-item` - a function that creates an items with a label
  - `ui-dropdown` - renders the dropdown. It requires the dropdown's properties, and allows optional named arguments:
     - `:onSelect` the callback for selection. It is given the selected element's id
     - `:kind` Identical to the `button` `:kind` attribute.

  All labels are run through `tr-unsafe`, so if a translation for the current locale exists it will be used.

  The following Om mutations are are available:

  Close (or open) a specific dropdown by ID:
  ```
  (om/transact! component `[(b/set-dropdown-open {:id :dropdown :open? false})]`)
  ```

  Close dropdowns (globally). Useful for capturing clicks a the root to close dropdowns when the user clicks outside of
  an open dropdown.

  ```
  (om/transact! component `[(b/close-all-dropdowns {})])
  ```

  An example usage:

  ```
  (defui DropdownRoot
    static uc/InitialAppState
    (initial-state [this props] {:dropdown (b/dropdown :file \"File\" [(b/dropdown-item :open \"Open\")
                                                                     (b/dropdown-item :close \"Close\")
                                                                     (b/dropdown-divider)
                                                                     (b/dropdown-item :quit \"Quit\")])})
    static om/IQuery
    (query [this] [{:dropdown (om/get-query b/Dropdown)}])
    Object
    (render [this]
      (let [{:keys [dropdown]} (om/props this)]
        (b/ui-dropdown dropdown :kind :success :onSelect (fn [id] (js/alert (str \"Selected: \" id)))))))
  ```

  generates the dropdown in the card below.
  ")

(defcard dropdown
  (untangled-app DropdownRoot))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Some internal helpers:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-example [width height & children]
  (ele/ui-iframe {:frameBorder 0 :height height :width width}
    (apply dom/div nil
      (dom/style nil ".boxed {border: 1px solid black}")
      (dom/link #js {:rel  "stylesheet"
                     :href "/bootstrap-3.3.7/css/bootstrap-theme.min.css"})
      (dom/link #js {:rel  "stylesheet"
                     :href "/bootstrap-3.3.7/css/bootstrap.min.css"})
      children)))

(defn sample [ele description]
  (dom/div #js {:className "thumbnail center-block"}
    ele
    (dom/div #js {:className "caption"}
      (dom/p #js {:className "text-center"} description))))
