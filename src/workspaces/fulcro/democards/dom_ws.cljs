(ns fulcro.democards.dom-ws
  (:require [fulcro.client.cards :refer [make-root]]
            [fulcro.client.dom :as dom :refer [div span]]
            [goog.object :as gobj]
            [nubank.workspaces.core :as ws]
            [nubank.workspaces.model :as wsm]
            [nubank.workspaces.card-types.fulcro :as ct.fulcro]
            [nubank.workspaces.lib.fulcro-portal :as f.portal]
            [fulcro.client.routing :as r :refer [defsc-router]]
            [fulcro.client.primitives :as prim :refer [defui defsc InitialAppState initial-state]]
            [fulcro.client.mutations :as m]
            [fulcro.client.localized-dom :as ldom]))

;; normally we get macro expansion, this will let us force calling the functions
(def fdiv div)
(def fspan span)
(def finput dom/input)

(defn js-classname [name] #js {:className name})
(defn bg-color-style [color] {:style {:backgroundColor color}})

(defsc AttrStatic [this props]
  (div
    (dom/section
      (dom/h4 "Lazy Sequences")
      (map #(dom/div {} %) ["A" (map #(dom/span {} %) ["B.1" "B.2"]) "C"])
      (map dom/div ["A" (map dom/span ["B.1" "B.2"]) "C"]))
    (dom/section
      (dom/h4 "Macros")
      (div "Attr is missing with a string child")
      (->> "String threaded through multiple DOM elements with various args" (span :.x {:className "a"}) (span #js {}) (div :.z))
      (div
        (span "attrs missing with a child element 1,")
        (span " and a child element 2"))
      (div nil "Attr is nil")
      (div {} "Attr is empty map")
      (div #js {} "Attr is empty js-object")
      (div {:className "foo"} "Attr adds css class")
      (div {:style {:backgroundColor "red"}} "Attr has nested inline style")
      (div (js-classname "foo") "Attr fn adds css class")
      (div (bg-color-style "red") "Attr fn has nested inline style"))
    (dom/section
      (dom/h4 "Functions")
      (fdiv "Attr is missing with a string child")
      (->> "String threaded through multiple DOM elements with various args" (span :.x {:className "a"}) (span #js {}) (div :.z))
      (fdiv
        (fspan "attrs missing with a child element 1,")
        (fspan " and a child element 2"))
      (fdiv nil "Attr is nil")
      (fdiv {} "Attr is empty map")
      (fdiv #js {} "Attr is empty js-object")
      (fdiv {:className "foo"} "Attr adds css class")
      (fdiv {:style {:backgroundColor "red"}} "Attr has nested inline style")
      (fdiv (js-classname "foo") "Attr fn adds css class")
      (fdiv (bg-color-style "red") "Attr fn has nested inline style"))))

(ws/defcard attr-static-enumeration
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root       AttrStatic
     ::f.portal/wrap-root? false}))

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

(ws/defcard attr-symbolic-enumeration
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root       AttrSymbolic
     ::f.portal/wrap-root? false}))

(defsc CssShorthand [this props]
  (let [x             nil
        symbolic-attr {:style {:backgroundColor "yellow"}}]
    (div
      (dom/style "#the-id {background-color: coral;}")
      (dom/style ".border-klass {border-style: solid;}")
      (dom/style ".color-klass {background-color: pink;}")
      (dom/section
        (dom/h3 "Macros")
        (div :#the-id.border-klass "Has a shorthand CSS for border class and coral background id")
        (div :.border-klass {:className "color-klass"}
          "Has a shorthand CSS for border class and pink color class in attrs")
        (div :.border-klass {:classes [:.color-klass]}
          "Has a shorthand CSS for border class and pink color class in classes attrs")
        (div {:classes [:.border-klass :.color-klass]}
          "Has no shorthand but border class and pink color class in classes attrs")
        (div :.border-klass {:style {:backgroundColor "violet"}}
          "Has a shorthand CSS for border class and violet background inline styles")
        (div :.border-klass x
          "Has a shorthand CSS for border class and symbolic nil attrs")
        (div :.border-klass nil
          "Has a shorthand CSS for border class and nil attrs")
        (div :.border-klass symbolic-attr
          "Has a shorthand CSS for border class and yellow background symbolic inline styles"))
      (dom/section
        (dom/h3 "Functions")
        (fdiv :#the-id.border-klass "Has a shorthand CSS for border class and coral background id")
        (fdiv :.border-klass {:className "color-klass"}
          "Has a shorthand CSS for border class and pink color class in attrs")
        (fdiv :.border-klass {:classes [:.color-klass]}
          "Has a shorthand CSS for border class and pink color class in classes attrs")
        (fdiv {:classes [:.border-klass :.color-klass]}
          "Has no shorthand but border class and pink color class in classes attrs")
        (fdiv :.border-klass {:style {:backgroundColor "violet"}}
          "Has a shorthand CSS for border class and violet background inline styles")
        (fdiv :.border-klass x
          "Has a shorthand CSS for border class and symbolic nil attrs")
        (fdiv :.border-klass nil
          "Has a shorthand CSS for border class and nil attrs")
        (fdiv :.border-klass symbolic-attr
          "Has a shorthand CSS for border class and yellow background symbolic inline styles")))))

(ws/defcard css-shorthand
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root       CssShorthand
     ::f.portal/wrap-root? true}))

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
  (dom/div nil
    (ui-form form)))

(defsc OldWrappedInputRoot [this {:keys [db/id form]}]
  {:query         [:db/id
                   {:form (prim/get-query OldForm)}]
   :ident         [:wrapped-input-root/by-id :db/id]
   :initial-state {:db/id 1 :form {}}}
  (dom/div nil
    (ui-old-form form)))

(ws/defcard old-wrapped-input-root
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root       OldWrappedInputRoot
     ::f.portal/wrap-root? false}))

(ws/defcard wrapped-input-root
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root       WrappedInputRoot
     ::f.portal/wrap-root? true}))

(defsc TextAreaTest [this props]
  {}
  (dom/div
    (dom/textarea {:value "This is a text area"})))

(ws/defcard text-area-card
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root       TextAreaTest
     ::f.portal/wrap-root? false}))


(defsc SelectTest [this props]
  {}
  (dom/div
    (dom/button {:onClick (fn [] (when-let [ele (gobj/get this "r")] (.focus ele)))} "Click Me to Focus the Select!")
    (dom/select {:value "c" :ref (fn [r] (gobj/set this "r" r))}
      (dom/option {:value "a" :label "A"})
      (dom/option {:value "b" :label "B"})
      (dom/option {:value "c" :label "C"})
      (dom/option {:value "d" :label "D"}))))

(ws/defcard select-test-card
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root       SelectTest
     ::f.portal/wrap-root? false}))

(defsc A [this props]
  {:query         [:a]
   :css           [[:.b {:color "black"}]]
   :initial-state {}}
  (dom/div nil "TODO"))

(def ui-a (prim/factory A {:keyfn :db/id}))

(defsc CSSStyleRoot [this props]
  {:query         [{:a (prim/get-query A)}]
   :initial-state {}
   :ident         (fn [] [:a 1])
   :css           [[:.a {:color "red"}]]}
  (dom/div
    #_(injection/style-element {:react-key (rand-int 120)   ; Include this to recompute CSS on every refresh
                                :component this})
    #_(injection/style-element {:react-key (rand-int 120)   ; Include this to recompute CSS on every refresh
                                :order     :breadth-first
                                :component this})
    (dom/button {:onClick #(prim/set-state! this {:n (rand-int 20)})} "Bump")

    (ldom/div :.a "Edit this card to check the various bits...")))

(ws/defcard css-style-root
  {::wsm/card-width 4 ::wsm/card-height 4}
  (ct.fulcro/fulcro-card
    {::f.portal/root       CSSStyleRoot
     ::f.portal/wrap-root? true}))
