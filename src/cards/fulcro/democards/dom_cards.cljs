(ns fulcro.democards.dom-cards
  (:require [devcards.core :as dc]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.alpha.dom :as dom :refer [div span]]
            [goog.object :as gobj]
            [fulcro.client.primitives :as prim :refer [defui defsc InitialAppState initial-state]]
            [fulcro.client.mutations :as m]))

(defsc AttrStatic [this props]
  (div
    (div "Attr is missing with a string child")
    (div
      (span "attrs missing with a child element 1,")
      (span " and a child element 2"))
    (div nil "Attr is nil")
    (div {} "Attr is empty map")
    (div #js {} "Attr is empty js-object")
    (div {:className "foo"} "Attr adds css class")
    (div {:style {:backgroundColor "red"}} "Attr has nested inline style")))

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
      (div x "Attr is nil")
      (div y "Attr is empty map")
      (div z "Attr is empty js-object")
      (div klass-info "Attr adds css class")
      (div {:style styles} "Attr has nested inline symbolic style")
      (div symbolic-attr "Attr has nested inline style and is all symbolic"))))

(defcard-fulcro attr-symbolic-enumeration
  "Part or all of these attrs are symbolic and resolved at runtime."
  AttrSymbolic)

(defsc CssShorthand [this props]
  (let [x             nil
        symbolic-attr {:style {:backgroundColor "yellow"}}]
    (div
      (dom/style "#the-id {background-color: coral;}")
      (dom/style ".border-klass {border-style: solid;}")
      (dom/style ".color-klass {background-color: pink;}")

      (div :#the-id.border-klass "Has a shorthand CSS for border class and coral background id")
      (div :.border-klass {:className "color-klass"}
        "Has a shorthand CSS for border class and pink color class in attrs")
      (div :.border-klass {:style {:backgroundColor "violet"}}
        "Has a shorthand CSS for border class and violet background inline styles")
      (div :.border-klass x
        "Has a shorthand CSS for border class and symbolic nil attrs")
      (div :.border-klass nil
        "Has a shorthand CSS for border class and nil attrs")
      (div :.border-klass symbolic-attr
        "Has a shorthand CSS for border class and yellow background symbolic inline styles"))))

(defcard-fulcro css-shorthand
  "These dom elements use the CSS id/class (both shorthand and in attrs) with style tags."
  CssShorthand)

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
  (dom/input {:onChange #(m/set-string! this :form/value :event %)
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
  (dom/div nil
    (ui-form form)))

(defsc OldWrappedInputRoot [this {:keys [db/id form]}]
  {:query         [:db/id
                   {:form (prim/get-query OldForm)}]
   :ident         [:wrapped-input-root/by-id :db/id]
   :initial-state {:db/id 1 :form {}}}
  (dom/div nil
    (ui-old-form form)))

(defcard-fulcro wrapped-input-card-string-refs
  "# Inputs with (Deprecated) String ref.

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
