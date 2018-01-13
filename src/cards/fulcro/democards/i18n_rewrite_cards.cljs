(ns fulcro.democards.i18n-rewrite-cards
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro]]
            yahoo.intl-messageformat-with-locales
            [fulcro.server :as server :refer [defquery-root]]
            [fulcro.client.impl.parser :as p]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.client.data-fetch :as df]
            [fulcro.localization :as i18n :refer [t]]
            [fulcro.client.logging :as log]))

(def mock-server-networking (server/new-server-emulator))

(defquery-root ::i18n/translations
  (value [env {:keys [locale]}]
    {::i18n/locale       locale
     ::i18n/locale-name  (case locale
                           "es" "Espanol"
                           "de" "Deutsch"
                           "en" "English")
     ::i18n/translations (cond
                           (= "es" locale) {"|Hello"          "Ola"
                                            "|It is {n,date}" "Es {n,date}"
                                            "|Hello, {name}"  "Ola, {name}"}
                           (= "de" locale) {"|Hello"          "Hallo"
                                            "|It is {n,date}" "Das ist {n,date}"
                                            "|Hello, {name}"  "Hallo, {name}"})}))

(defsc Child [this props]
  {:query         [:ui/checked?]
   :initial-state {:ui/checked? false}}
  (dom/div nil
    (dom/p nil (t this "Hello, {name}" {:name "Sam"}))
    (dom/p nil (t this "It is {n,date}" {:n (js/Date.)}))
    (t this "Hello")))

(def ui-child (prim/factory Child))

(defsc Root [this {:keys [child locale-selector]}]
  {:query         [{:locale-selector (prim/get-query i18n/LocaleSelector)}
                   {::i18n/current-locale (prim/get-query i18n/Locale)}
                   {:child (prim/get-query Child)}]
   :initial-state {:child                {}
                   ::i18n/current-locale {:locale "en" :name "English" :translations {}}
                   :locale-selector      {:locales [{:locale "en" :name "English"}
                                                    {:locale "es" :name "Espanol"}
                                                    {:locale "de" :name "Deutsch"}]}}}
  (dom/div nil
    (i18n/ui-locale-selector locale-selector)
    (ui-child child)))

(defcard-fulcro sample
  Root
  {}
  {:inspect-data false
   :fulcro       {:networking         mock-server-networking
                  :reconciler-options {:shared-fn ::i18n/current-locale}}})
