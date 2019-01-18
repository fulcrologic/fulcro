(ns fulcro.democards.localized-dom-cards
  (:require [devcards.core :as dc]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.localized-dom :as dom :refer [div span]]
            [goog.object :as gobj]
            [fulcro.client.primitives :as prim :refer [defui defsc InitialAppState initial-state]]
            [fulcro.client.mutations :as m]
            [fulcro-css.css :as css]))

(def fdiv div)
(def fspan span)
(def finput dom/input)

(defsc AttrStatic [this props]
  (div
    (dom/section
      (dom/h4 "Lazy Sequences")
      (map #(dom/div {} %) ["A" (map #(dom/span {} %) ["B.1" "B.2"]) "C"])
      (map dom/div ["A" (map dom/span ["B.1" "B.2"]) "C"]))
    (dom/section
      (dom/h4 "Macros")
      (div "Attr is missing with a string child")
      (div
        (span "attrs missing with a child element 1,")
        (span " and a child element 2"))
      (div nil "Attr is nil")
      (div {} "Attr is empty map")
      (div #js {} "Attr is empty js-object")
      (div {:className "foo"} "Attr adds css class")
      (div {:style {:backgroundColor "red"}} "Attr has nested inline style"))
    (dom/section
      (dom/h4 "Functions")
      (div "Attr is missing with a string child")
      (fdiv
        (fspan "attrs missing with a child element 1,")
        (fspan " and a child element 2"))
      (fdiv nil "Attr is nil")
      (fdiv {} "Attr is empty map")
      (fdiv #js {} "Attr is empty js-object")
      (fdiv {:className "foo"} "Attr adds css class")
      (fdiv {:style {:backgroundColor "red"}} "Attr has nested inline style"))))

(defcard-fulcro attr-static-enumeration
  "These attrs can be reasoned about at compile time."
  AttrStatic)

(defsc AttrSymbolic [this props]
  (let [x             nil
        y             {}
        z             #js {}
        klass-info    {:className "foo"}
        styles        {:backgroundColor "red"}
        symbolic-attr {:style {:backgroundColor "green"}}]
    (div
      (dom/section
        (dom/h4 "Macros")
        (div x "Attr is nil")
        (div y "Attr is empty map")
        (div z "Attr is empty js-object")
        (div klass-info "Attr adds css class")
        (div {:style styles} "Attr has nested inline symbolic style")
        (div symbolic-attr "Attr has nested inline style and is all symbolic"))
      (dom/section
        (dom/h4 "Functions")
        (fdiv x "Attr is nil")
        (fdiv y "Attr is empty map")
        (fdiv z "Attr is empty js-object")
        (fdiv klass-info "Attr adds css class")
        (fdiv {:style styles} "Attr has nested inline symbolic style")
        (fdiv symbolic-attr "Attr has nested inline style and is all symbolic")))))

(defcard-fulcro attr-symbolic-enumeration
  "Part or all of these attrs are symbolic and resolved at runtime."
  AttrSymbolic)

(defsc SharedPropChild [this props]
  (let [s (prim/shared this)]
    (dom/div nil (pr-str s))))

(def ui-shared-prop-child (prim/factory SharedPropChild {:keyfn :db/id}))

(defsc CssShorthand [this props _ {:keys [color-klass]}]
  {:css [[:#the-id {:background-color :coral}]
         [:.border-klass {:border-style :solid}]
         [:.color-klass {:background-color :pink}]]}
  (let [x             nil
        symbolic-attr {:style {:backgroundColor "yellow"}}]
    (div
      (dom/section
        (dom/h4 "Lazy Sequences (macro)")
        (map #(dom/div {} %) ["A" (map #(dom/span {} %) ["B.1" "B.2" (ui-shared-prop-child {})]) "C"])
        (dom/h4 "Lazy Sequences (runtime)")
        (map #(dom/div :.a %) ["A" (map #(dom/span :.a %) ["B.1" "B.2" (ui-shared-prop-child {})]) "C"]))
      (dom/section
        (dom/h4 "Macros")
        (div :#the-id.border-klass "choral bg with border. Via localized kw")
        (div :.border-klass {:className color-klass} "pink bg with border. via kw + className")
        (div :.border-klass {:style {:backgroundColor "violet"}} "violet bg with border. via kw + inline styles")
        (div :.border-klass x "white bg with border. Via kw + sym")
        (div :.border-klass nil "white bg with border. Via kw + nil")
        (div :.border-klass symbolic-attr "yellow bg with border. Via kw + sym with inline styles"))
      (dom/section
        (dom/h4 "Functions")
        (fdiv :#the-id.border-klass "choral bg with border. Via localized kw")
        (fdiv :.border-klass {:className color-klass} "pink bg with border. via kw + className")
        (fdiv :.border-klass {:style {:backgroundColor "violet"}} "violet bg with border. via kw + inline styles")
        (fdiv :.border-klass x "white bg with border. Via kw + sym")
        (fdiv :.border-klass nil "white bg with border. Via kw + nil")
        (fdiv :.border-klass symbolic-attr "yellow bg with border. Via kw + sym with inline styles")))))

(defcard-fulcro css-shorthand
  "These dom elements use the CSS id/class (both shorthand and in attrs) with style tags."
  CssShorthand
  {}
  {:fulcro {:reconciler-options {:shared {:a 1}}
            :started-callback   (fn [app]
                                  (css/upsert-css "css-shorthand-styles" CssShorthand))}})

(defsc Form [this {:keys [:db/id :form/value] :as props}]
  {:query             [:db/id :form/value]
   :ident             [:form/by-id :db/id]
   :initial-state     {:db/id 1 :form/value 22}
   :componentDidMount (fn [] (when-let [e (gobj/get this "n")] (.focus e)))}
  (dom/input :#id.cls {:onChange #(m/set-string! this :form/value :event %)
                       :ref      (fn [r] (gobj/set this "n" r))
                       :value    value}))

(defsc OldForm [this {:keys [:db/id :form/value] :as props}]
  {:query             [:db/id :form/value]
   :ident             [:form/by-id :db/id]
   :initial-state     {:db/id 1 :form/value 22}
   :componentDidMount (fn [] (when-let [e (dom/node this "thing")] (.focus e)))}
  (finput {:onChange #(m/set-string! this :form/value :event %)
           :ref      "thing"
           :value    value}))

(def ui-form (prim/factory Form {:keyfn :db/id}))
(def ui-old-form (prim/factory OldForm {:keyfn :db/id}))

(defsc WrappedInputRoot [this {:keys [db/id form old-form]}]
  {:query         [:db/id
                   {:form (prim/get-query Form)}
                   {:old-form (prim/get-query OldForm)}]
   :ident         [:wrapped-input-root/by-id :db/id]
   :initial-state {:db/id 1 :form {}}}
  (dom/div
    (ui-form form)))

(defsc OldWrappedInputRoot [this {:keys [db/id form]}]
  {:query         [:db/id
                   {:form (prim/get-query OldForm)}]
   :ident         [:wrapped-input-root/by-id :db/id]
   :initial-state {:db/id 1 :form {}}}
  (dom/div
    (ui-old-form form)))

(defcard-fulcro wrapped-input-card-string-refs
  "# Function Inputs with (Deprecated) String ref.

  Click on the card to show only this card and reload the page (it should auto-focus on mount)"
  OldWrappedInputRoot
  {}
  {:inspect-data true})

(defcard-fulcro wrapped-input-card-fn-refs
  "# Input with fn ref

  Click on the card to show only this card and reload the page (it should auto-focus on mount)"
  WrappedInputRoot
  {}
  {:inspect-data true})
