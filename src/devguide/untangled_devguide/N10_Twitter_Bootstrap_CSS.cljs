(ns untangled-devguide.N10-Twitter-Bootstrap-CSS
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc deftest]]
            [cljs.reader :as r]
            [om.next.impl.parser :as p]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [untangled.ui.elements :as ele]
            [untangled.client.cards :refer [untangled-app]]
            [untangled.client.mutations :as m]
            [untangled.ui.bootstrap3 :as b]
            [untangled.client.core :as uc]
            [untangled.ui.html-entities :as ent]))

(declare render-example sample)

(defcard-doc
  "
  # Twitter Bootstrap

  Untangled includes functions that emit the DOM with CSS for version 3 of Twitter's Bootstrap CSS and Components.

  NOTE: You must include version 3 of Bootstrap's CSS (and optionally theme), but *not* js, in your HTML file.

  The CSS affects many DOM elements, which means you'll see examples that use Om's DOM functions, which
  in turn require a JavaScript object as the first argument (for performance). The helper functions from the
  `bootstrap` namespace need to modify the incoming arguments, so the first argument (if it takes DOM props) is
  a cljs map instead.

  ```
  ; render an Om regular DOM element:
  (dom/div #js { :className \"a\" })
  ; render a bootstrap element via one of these functions
  (b/button { :className \"b\"} \"Button label\")
  ```

  The javascript object verion with regular DOM elements avoid data conversion overhead at runtime. The bootstrap functions
  in this library need to augment and manipulate the passed parameters, so they use cljs maps instead.

  This documentation covers the passive elements that largely add in proper structure and classnames for bootstrap rendering. The
  other sections on bootstrap cover active elements.

  Untangled now also includes `untangled.ui.html-entities` (which we alias to `ent` here). These just give symbol names
  to some of the more popular HTML entities (e.g. `&times;` is `ent/times`) so they are easier to use in React.
  ")

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

(defcard breadcrumbs
  "Rendering breadcrumbs"
  (render-example "100%" "75px"
    (b/breadcrumbs {}
      (b/breadcrumb-item "Home" (fn [] (js/alert "Go home")))
      (b/breadcrumb-item "Reports" (fn [] (js/alert "Go Reports")))
      (b/breadcrumb-item "Report A"))))

(defcard pagination
  "Rendering pagination

  The pagination control is pure UI.

  The first example below is:

  ```
  (b/pagination {}
    (b/pagination-entry {:label ent/laqao :onClick #(js/alert \"Back\")})
    (b/pagination-entry {:label \"1\" :onClick #(js/alert \"1\")})
    (b/pagination-entry {:label \"2\" :onClick #(js/alert \"2\")})
    (b/pagination-entry {:label \"3\" :onClick #(js/alert \"3\")})
    (b/pagination-entry {:label ent/raqao :onClick #(js/alert \"Next\")}))
  ```

  The second example below is:

  ```
  (b/pagination {}
    (b/pagination-entry {:label ent/laqao :onClick #(js/alert \"Back\")})
    (b/pagination-entry {:label \"1\" :active true :onClick #(js/alert \"1\")})
    (b/pagination-entry {:label \"2\" :onClick #(js/alert \"2\")})
    (b/pagination-entry {:label \"3\" :onClick #(js/alert \"3\")})
    (b/pagination-entry {:label ent/raqao :disabled true :onClick #(js/alert \"Next\")}))
  ```
  "
  (render-example "100%" "375px"
    (b/table {:styles #{:condensed}}
      (dom/tbody nil
        (dom/tr nil
          (dom/th nil "Pages (none active)")
          (dom/td nil (b/pagination {}
                        (b/pagination-entry {:label ent/laqao :onClick #(js/alert "Back")})
                        (b/pagination-entry {:label "1" :onClick #(js/alert "1")})
                        (b/pagination-entry {:label "2" :onClick #(js/alert "2")})
                        (b/pagination-entry {:label "3" :onClick #(js/alert "3")})
                        (b/pagination-entry {:label ent/raqao :onClick #(js/alert "Next")}))))
        (dom/tr nil
          (dom/th nil "With one active, and next disabled")
          (dom/td nil (b/pagination {}
                        (b/pagination-entry {:label ent/laqao :onClick #(js/alert "Back")})
                        (b/pagination-entry {:label "1" :active true :onClick #(js/alert "1")})
                        (b/pagination-entry {:label "2" :onClick #(js/alert "2")})
                        (b/pagination-entry {:label "3" :onClick #(js/alert "3")})
                        (b/pagination-entry {:label ent/raqao :disabled true :onClick #(js/alert "Next")}))))
        (dom/tr nil
          (dom/th nil "Small")
          (dom/td nil (b/pagination {:size :sm}
                        (b/pagination-entry {:label ent/laqao :onClick #(js/alert "Back")})
                        (b/pagination-entry {:label "1" :onClick #(js/alert "1")})
                        (b/pagination-entry {:label "2" :onClick #(js/alert "2")})
                        (b/pagination-entry {:label "3" :onClick #(js/alert "3")})
                        (b/pagination-entry {:label ent/raqao :onClick #(js/alert "Next")}))))
        (dom/tr nil
          (dom/th nil "Large")
          (dom/td nil (b/pagination {:size :lg}
                        (b/pagination-entry {:label ent/laqao :onClick #(js/alert "Back")})
                        (b/pagination-entry {:label "1" :onClick #(js/alert "1")})
                        (b/pagination-entry {:label "2" :onClick #(js/alert "2")})
                        (b/pagination-entry {:label "3" :onClick #(js/alert "3")})
                        (b/pagination-entry {:label ent/raqao :onClick #(js/alert "Next")}))))))))

(defcard pager
  "# Pager - A lightweight next/prior control.

  ```
  (b/pager {}
    (b/pager-previous {:onClick #(js/alert \"Back\")}
      (str ent/laqao \" Back\"))
    (b/pager-next {:onClick #(js/alert \"Next\")}
      (str \"Next \" ent/raqao)))
  ```
  "
  (render-example "100%" "75px"
    (b/pager {}
      (b/pager-previous {:onClick #(js/alert "Back")}
        (str ent/laqao " Back"))
      (b/pager-next {:onClick #(js/alert "Next")}
        (str "Next " ent/raqao)))))


(defcard alerts
  "# Alerts

  `(b/alert {:kind :success} \"Oh boy!\")`

  An box with a close button and contextual :kind (:success, :danger, :warning, ...), and
  optional `onClose` handler you supply.
  "
  (render-example "100%" "300px"
    (b/alert {:kind :success} "Oh boy!")
    (dom/br nil)
    (b/alert nil "Things went wrong!")
    (dom/br nil)
    (b/alert {:onClose identity :kind :warning} "I'm worried. (with an onClose)")))

(defcard badges
  "# Badges

  An inline badge that can be placed on other elements.

  `(b/button {} \"Inbox \" (b/badge {} \"1\"))`
  "
  (render-example "100%" "100px"
    (b/button {} "Inbox " (b/badge {} "1"))))

(defcard jumbotron
  "# Jumbotron

  A container that offsets the content. Note, this will not work if it isn't in the grid:

  ```
  (b/container nil
    (b/row nil
      (b/col {:xs 8 :xs-offset 2}
        (b/jumbotron {}
          (dom/h1 nil \"Title\")
          (dom/p nil \"There is some fancy stuff going on here!\")
          (b/button {:kind :primary} \"Learn More!\")))))
  ```
  "
  (render-example "100%" "320px"
    (b/container nil
      (b/row nil
        (b/col {:xs 8 :xs-offset 2}
          (b/jumbotron {}
            (dom/h1 nil "Title")
            (dom/p nil "There is some fancy stuff going on here!")
            (b/button {:kind :primary} "Learn More!")))))))

(defcard thumbnails-and-captions
  "# Thumbnails and Captions

  Thumbnails place a border around a box. Can be combined with images and captions in a grid to make
  Pinterest-style blocks of content.

  ```
  (b/container nil
    (b/row nil
      (b/col {:sm 4 }
        (b/thumbnail nil
          (b/img {:src \"data:image/svg+xml;base64,PD9...\"})
          (b/caption nil
            (dom/h3 nil \"Title\")
            (dom/p nil \"Some content underneath the title with enough words to cause a little wrapping.\"))))
      (b/col {:sm 4 }
        (b/thumbnail nil
          (b/img {:src \"data:image/svg+xml;base64,PD9...\"})
          (b/caption nil
            (dom/h3 nil \"Title\")
            (dom/p nil \"Some content underneath the title with enough words to cause a little wrapping.\"))))
      (b/col {:sm 4 }
        (b/thumbnail nil
          (b/img {:src \"data:image/svg+xml;base64,PD9...\"})
          (b/caption nil
            (dom/h3 nil \"Title\")
            (dom/p nil \"Some content underneath the title with enough words to cause a little wrapping.\"))))))
  ```
  "
  (render-example "100%" "360px"
    (b/container nil
      (b/row nil
        (b/col {:sm 4}
          (b/thumbnail nil
            (b/img {:src "data:image/svg+xml;base64,PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0iVVRGLTgiIHN0YW5kYWxvbmU9InllcyI/PjxzdmcgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIiB3aWR0aD0iMjQyIiBoZWlnaHQ9IjIwMCIgdmlld0JveD0iMCAwIDI0MiAyMDAiIHByZXNlcnZlQXNwZWN0UmF0aW89Im5vbmUiPjwhLS0KU291cmNlIFVSTDogaG9sZGVyLmpzLzEwMCV4MjAwCkNyZWF0ZWQgd2l0aCBIb2xkZXIuanMgMi42LjAuCkxlYXJuIG1vcmUgYXQgaHR0cDovL2hvbGRlcmpzLmNvbQooYykgMjAxMi0yMDE1IEl2YW4gTWFsb3BpbnNreSAtIGh0dHA6Ly9pbXNreS5jbwotLT48ZGVmcz48c3R5bGUgdHlwZT0idGV4dC9jc3MiPjwhW0NEQVRBWyNob2xkZXJfMTVjYTk5NmQ1M2YgdGV4dCB7IGZpbGw6I0FBQUFBQTtmb250LXdlaWdodDpib2xkO2ZvbnQtZmFtaWx5OkFyaWFsLCBIZWx2ZXRpY2EsIE9wZW4gU2Fucywgc2Fucy1zZXJpZiwgbW9ub3NwYWNlO2ZvbnQtc2l6ZToxMnB0IH0gXV0+PC9zdHlsZT48L2RlZnM+PGcgaWQ9ImhvbGRlcl8xNWNhOTk2ZDUzZiI+PHJlY3Qgd2lkdGg9IjI0MiIgaGVpZ2h0PSIyMDAiIGZpbGw9IiNFRUVFRUUiLz48Zz48dGV4dCB4PSI4OS44NTkzNzUiIHk9IjEwNS40Ij4yNDJ4MjAwPC90ZXh0PjwvZz48L2c+PC9zdmc+"})
            (b/caption nil
              (dom/h3 nil "Title")
              (dom/p nil "Some content underneath the title with enough words to cause a little wrapping."))))
        (b/col {:sm 4}
          (b/thumbnail nil
            (b/img {:src "data:image/svg+xml;base64,PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0iVVRGLTgiIHN0YW5kYWxvbmU9InllcyI/PjxzdmcgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIiB3aWR0aD0iMjQyIiBoZWlnaHQ9IjIwMCIgdmlld0JveD0iMCAwIDI0MiAyMDAiIHByZXNlcnZlQXNwZWN0UmF0aW89Im5vbmUiPjwhLS0KU291cmNlIFVSTDogaG9sZGVyLmpzLzEwMCV4MjAwCkNyZWF0ZWQgd2l0aCBIb2xkZXIuanMgMi42LjAuCkxlYXJuIG1vcmUgYXQgaHR0cDovL2hvbGRlcmpzLmNvbQooYykgMjAxMi0yMDE1IEl2YW4gTWFsb3BpbnNreSAtIGh0dHA6Ly9pbXNreS5jbwotLT48ZGVmcz48c3R5bGUgdHlwZT0idGV4dC9jc3MiPjwhW0NEQVRBWyNob2xkZXJfMTVjYTk5NmQ1M2YgdGV4dCB7IGZpbGw6I0FBQUFBQTtmb250LXdlaWdodDpib2xkO2ZvbnQtZmFtaWx5OkFyaWFsLCBIZWx2ZXRpY2EsIE9wZW4gU2Fucywgc2Fucy1zZXJpZiwgbW9ub3NwYWNlO2ZvbnQtc2l6ZToxMnB0IH0gXV0+PC9zdHlsZT48L2RlZnM+PGcgaWQ9ImhvbGRlcl8xNWNhOTk2ZDUzZiI+PHJlY3Qgd2lkdGg9IjI0MiIgaGVpZ2h0PSIyMDAiIGZpbGw9IiNFRUVFRUUiLz48Zz48dGV4dCB4PSI4OS44NTkzNzUiIHk9IjEwNS40Ij4yNDJ4MjAwPC90ZXh0PjwvZz48L2c+PC9zdmc+"})
            (b/caption nil
              (dom/h3 nil "Title")
              (dom/p nil "Some content underneath the title with enough words to cause a little wrapping."))))
        (b/col {:sm 4}
          (b/thumbnail nil
            (b/img {:src "data:image/svg+xml;base64,PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0iVVRGLTgiIHN0YW5kYWxvbmU9InllcyI/PjxzdmcgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIiB3aWR0aD0iMjQyIiBoZWlnaHQ9IjIwMCIgdmlld0JveD0iMCAwIDI0MiAyMDAiIHByZXNlcnZlQXNwZWN0UmF0aW89Im5vbmUiPjwhLS0KU291cmNlIFVSTDogaG9sZGVyLmpzLzEwMCV4MjAwCkNyZWF0ZWQgd2l0aCBIb2xkZXIuanMgMi42LjAuCkxlYXJuIG1vcmUgYXQgaHR0cDovL2hvbGRlcmpzLmNvbQooYykgMjAxMi0yMDE1IEl2YW4gTWFsb3BpbnNreSAtIGh0dHA6Ly9pbXNreS5jbwotLT48ZGVmcz48c3R5bGUgdHlwZT0idGV4dC9jc3MiPjwhW0NEQVRBWyNob2xkZXJfMTVjYTk5NmQ1M2YgdGV4dCB7IGZpbGw6I0FBQUFBQTtmb250LXdlaWdodDpib2xkO2ZvbnQtZmFtaWx5OkFyaWFsLCBIZWx2ZXRpY2EsIE9wZW4gU2Fucywgc2Fucy1zZXJpZiwgbW9ub3NwYWNlO2ZvbnQtc2l6ZToxMnB0IH0gXV0+PC9zdHlsZT48L2RlZnM+PGcgaWQ9ImhvbGRlcl8xNWNhOTk2ZDUzZiI+PHJlY3Qgd2lkdGg9IjI0MiIgaGVpZ2h0PSIyMDAiIGZpbGw9IiNFRUVFRUUiLz48Zz48dGV4dCB4PSI4OS44NTkzNzUiIHk9IjEwNS40Ij4yNDJ4MjAwPC90ZXh0PjwvZz48L2c+PC9zdmc+"})
            (b/caption nil
              (dom/h3 nil "Title")
              (dom/p nil "Some content underneath the title with enough words to cause a little wrapping."))))))))

(defcard progress-bars
  "# Progress Bars

  `(b/progress-bar {:kind :success :current (:pct @state)})`
  `(b/progress-bar {:kind :success :animated? (< (:pct @state) 100) :current (:pct @state)})`
  "
  (fn [state _]
    (render-example "100%" "120px"
      (b/button {:onClick (fn [] (swap! state update :pct #(- % 10)))} "Less")
      (b/button {:onClick (fn [] (swap! state update :pct #(+ 10 %)))} "More")
      (b/progress-bar {:kind :success :current (:pct @state)})
      (b/progress-bar {:kind :success :animated? (< (:pct @state) 100) :current (:pct @state)})))
  {:pct 40}
  {:inspect-data true})

(defcard panels
  "# Panels

  ```
    (b/panel nil
      (b/panel-heading nil \"Heading without title\")
      (b/panel-body nil \"This is the body of the panel\")
      (b/panel-footer nil \"The footer\"))
    (b/panel {:kind :danger}
      (b/panel-heading nil
        (b/panel-title nil \"Panel Title of :danger panel\"))
      (b/panel-body nil \"This is the body of the panel\"))))
  ```

  NOTE: Tables can replace or follow the `panel-body`. They need not be placed within it.

  ```
  (b/panel nil
    (b/panel-heading nil
      (b/panel-title nil \"Panel with a table and no panel-body\"))
    (b/table nil
      (dom/tbody nil
        (dom/tr  nil
          (dom/th nil \"Name\")
          (dom/th nil \"Address\")
          (dom/th nil \"Phone\"))
        (dom/tr  nil
          (dom/td nil \"Sally\")
          (dom/td nil \"555 N Nowhere\")
          (dom/td nil \"555-1212\")))))
  ```

  "
  (render-example "100%" "420px"
    (b/panel nil
      (b/panel-heading nil "Heading without title")
      (b/panel-body nil "This is the body of the panel")
      (b/panel-footer nil "The footer"))
    (b/panel {:kind :danger}
      (b/panel-heading nil
        (b/panel-title nil "Panel Title of :danger panel"))
      (b/panel-body nil "This is the body of the panel"))
    (b/panel nil
      (b/panel-heading nil
        (b/panel-title nil "Panel with a table and no panel-body"))
      (b/table nil
        (dom/tbody nil
          (dom/tr nil
            (dom/th nil "Name")
            (dom/th nil "Address")
            (dom/th nil "Phone"))
          (dom/tr nil
            (dom/td nil "Sally")
            (dom/td nil "555 N Nowhere")
            (dom/td nil "555-1212")))))))


(defcard well
  "# Well

  Inset some content.

  `(b/well nil \"This is some content\")`
  "
  (render-example "100%" "100px"
    (b/well nil "This is some content")))

(defcard popover
  "# A popover

  Popovers are real React components, thus the factory has a ui prefix. They do not have state, so they are fed
  their orientation and current state via props. You can include a single child (tree), and the popover will use
  that as its boundary for locating itself.

  `(b/ui-popover {:orientation :top :active true} DOM-TO-TARGET)`
  "
  (fn [state _]
    (render-example "100%" "300px"
      (b/button {:onClick #(swap! state update :active not)} "Toggle All")
      (dom/br nil)
      (dom/br nil)
      (dom/br nil)
      (dom/br nil)
      (dom/br nil)
      (dom/br nil)
      (b/container nil
        (b/row nil
          (b/col {:xs-offset 2 :xs 2}
            (b/ui-popover {:active (:active @state) :orientation :left}
              (b/button {} "Left")))
          (b/col {:xs 2}
            (b/ui-popover {:active (:active @state) :orientation :top}
              (b/glyphicon {} :question-sign)))
          (b/col {:xs 2}
            (b/ui-popover {:active (:active @state) :orientation :bottom}
              (b/button {} "Bottom")))
          (b/col {:xs 2}
            (b/ui-popover {:active (:active @state) :orientation :right}
              (b/glyphicon {:size "33pt"} :question-sign)
              ))))))
  {:active false})


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
