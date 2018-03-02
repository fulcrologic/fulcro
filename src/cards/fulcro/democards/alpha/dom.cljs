(ns fulcro.democards.alpha.dom
  (:require [devcards.core :as dc]
            [fulcro.client :as fc]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.dom :as old-dom]
            [fulcro.client.alpha.dom :as dom]
            [goog.object :as gobj]
            [fulcro.client.primitives :as prim :refer [defui defsc InitialAppState initial-state]]
            [fulcro.client.mutations :as m]))

(defsc AttrStatic [this props]
  (dom/div nil
    (dom/div nil "Attr is nil")
    (dom/div {} "Attr is empty object")
    (dom/div #js {} "Attr is empty js-object")
    (dom/div {:className "foo"} "Attr adds css class")
    (dom/div {:style #js {:backgroundColor "red"}} "Attr has nested inline style")))

(defcard-fulcro attr-static-enumeration
  "These attrs can be reasoned about at compile time."
  AttrStatic)

(defsc AttrSymbolic [this props]
  (let [x          nil
        y          {}
        z          #js {}
        klass-info {:className "foo"}
        styles     #js {:backgroundColor "red"}]
    (dom/div nil
      (dom/div x "Attr is nil")
      (dom/div y "Attr is empty object")
      (dom/div z "Attr is empty js-object")
      (dom/div klass-info "Attr adds css class")
      (dom/div {:style styles} "Attr has nested inline style"))))

(defcard-fulcro attr-symbolic-enumeration
  "Part or all of these attrs are symbolic and resolved at runtime."
  AttrSymbolic)

(defsc Form [this {:keys [:db/id :form/value] :as props}]
  {:query             [:db/id :form/value]
   :ident             [:form/by-id :db/id]
   :initial-state     {:db/id 1 :form/value 22}
   :componentDidMount (fn [] (when-let [e (gobj/get this "n")] (.focus e)))}
  (old-dom/input #js {:onChange #(m/set-string! this :form/value :event %)
                      :ref      (fn [r] (gobj/set this "n" r))
                      :value    value}))

(defsc OldForm [this {:keys [:db/id :form/value] :as props}]
  {:query             [:db/id :form/value]
   :ident             [:form/by-id :db/id]
   :initial-state     {:db/id 1 :form/value 22}
   :componentDidMount (fn [] (when-let [e (dom/node this "thing")] (.focus e)))}
  (old-dom/input #js {:onChange #(m/set-string! this :form/value :event %)
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
