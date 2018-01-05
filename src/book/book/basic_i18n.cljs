(ns book.basic-i18n
  (:require
    [fulcro.client.dom :as dom]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.i18n :as ic :refer-macros [tr trc trf]]
    yahoo.intl-messageformat-with-locales))

;; a manual installation of translations...this is for the demo only. Read more about auto-extraction and locale
; generation that automates this.
(reset! ic/*loaded-translations* {"es" {"|This is a test" "Spanish for 'this is a test'"
                                        "|Hi, {name}"     "Ola, {name}"}})

(defn locale-switcher [comp]
  (dom/div nil
    (dom/button #js {:onClick #(prim/transact! comp `[(m/change-locale {:lang "en"})])} "en")
    (dom/button #js {:onClick #(prim/transact! comp `[(m/change-locale {:lang "es"})])} "es")))

(defsc Format [this {:keys [ui/label]}]
  {:initial-state {:ui/label "Your Name"}
   :ident         (fn [] [:components :ui])
   :query         [:ui/label]}
  (dom/div nil
    (locale-switcher this)
    (dom/input #js {:value label :onChange #(m/set-string! this :ui/label :event %)})
    (trf "Hi, {name}" :name label)
    (dom/br nil)
    (trf "N: {n, number} ({m, date, long})" {:n 10229 :m (new js/Date)})
    (dom/br nil)))

(def ui-format (prim/factory Format))

(defsc Root [this {:keys [ui/locale format]}]
  {:initial-state (fn [p] {:format (prim/get-initial-state Format {})})
   :query         [:ui/locale {:format (prim/get-query Format)}]}
  (dom/div nil
    (dom/span nil (str "Locale: " locale))
    (dom/br nil)
    (ui-format format)))
