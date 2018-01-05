(ns book.demos.dynamic-i18n
  (:require [fulcro.client.dom :as dom]
            [fulcro.i18n :refer [tr]]
            [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.client.mutations :as m]))

(defsc Root [this {:keys [ui/locale]}]
  {:query [:ui/locale]}
  (dom/div nil
    (dom/h4 nil (tr "Locale Tests. Current locale: ") (name locale))
    (dom/p nil (tr "This is a test."))
    (mapv (fn [l] (dom/button #js {:key l :onClick #(prim/transact! this `[(m/change-locale {:lang ~l})])} l))
      ["en" "es-MX" "de"])))

