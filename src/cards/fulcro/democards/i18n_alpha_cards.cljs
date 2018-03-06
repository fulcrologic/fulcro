(ns fulcro.democards.i18n-alpha-cards
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro]]
            yahoo.intl-messageformat-with-locales
            [fulcro.server :as server :refer [defquery-root]]
            [fulcro.client.impl.parser :as p]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.client.data-fetch :as df]
            [fulcro.alpha.i18n :as i18n :refer [tr trc trf]]
            [fulcro.client.logging :as log]))

(def mock-server-networking (server/new-server-emulator))

(defquery-root ::i18n/translations
  (value [env {:keys [locale]}]
    ; in clj, you can put the PO files on the classpath or disk and use i18n/load-locale, which will return exactly this format
    {::i18n/locale       locale
     ::i18n/translations (cond
                           (= :es locale) {["" "Hello"]          "Ola"
                                           ["" "It is {n,date}"] "Es {n,date}"
                                           ["" "Hello, {name}"]  "Ola, {name}"}
                           (= :de locale) {["" "Hello"]          "Hallo"
                                           ["" "It is {n,date}"] "Das ist {n,date}"
                                           ["" "Hello, {name}"]  "Hallo, {name}"})}))

(defsc Child [this props]
  {:query         [:ui/checked?]
   :initial-state {:ui/checked? false}}
  (dom/div nil
    (dom/p nil (trf "Hello, {name}" {:name "Sam"}))
    (dom/p nil (trf "It is {n,date}" {:n (js/Date.)}))
    (dom/p nil (trc "Gender abbreviation" "M"))
    (tr "Hello")))

(def ui-child (prim/factory Child))

(defsc Root [this {:keys [child locale-selector]}]
  {:query         [{:locale-selector (prim/get-query i18n/LocaleSelector)}
                   {::i18n/current-locale (prim/get-query i18n/Locale)}
                   {:child (prim/get-query Child)}]
   :initial-state (fn [p]
                    {:child                (prim/get-initial-state Child {})
                     ::i18n/current-locale (prim/get-initial-state i18n/Locale {:locale :en :name "English" :translations {}})
                     :locale-selector      (prim/get-initial-state i18n/LocaleSelector {:locales [(prim/get-initial-state i18n/Locale {:locale :en :name "English"})
                                                                                                  (prim/get-initial-state i18n/Locale {:locale :es :name "Espanol"})
                                                                                                  (prim/get-initial-state i18n/Locale {:locale :de :name "Deutsch"})]})})}
  (dom/div nil
    (i18n/ui-locale-selector locale-selector)
    (ui-child child)))

(defn message-formatter [{:keys [::i18n/localized-format-string ::i18n/locale ::i18n/format-options]}]
  (let [locale-str (name locale)
        formatter  (js/IntlMessageFormat. localized-format-string locale-str)]
    (.format formatter (clj->js format-options))))

(defcard-fulcro sample
  Root
  {}
  {:inspect-data true
   :fulcro       {:networking         mock-server-networking
                  :reconciler-options {:shared    {::i18n/message-formatter message-formatter}
                                       :shared-fn ::i18n/current-locale}}})
