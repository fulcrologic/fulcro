(ns fulcro.democards.alpha.dom
  (:require [devcards.core :as dc]
            [fulcro.client :as fc]
            [fulcro.client.cards :refer [defcard-fulcro]]
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
