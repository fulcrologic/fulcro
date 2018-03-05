(ns fulcro.alpha.i18n-spec
  (:require [fulcro-spec.core :refer [specification behavior provided when-mocking assertions]]
            [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.alpha.i18n :as i18n :refer [tr trf trc]]
            [fulcro.server-render :as ssr]
            [fulcro.client.dom :as dom]
            [fulcro.logging :as log]
            [clojure.string :as str])
  (:import (java.util Date)))

(specification "Locale loading from PO files." :focused
  (when-mocking
    (log/-log l lvl & args) => (assertions
                                 "Logs an error when no locale is found"
                                 lvl => :error)

    (let [xlation         (i18n/load-locale "fulcro/alpha" :es)
          missing-xlation (i18n/load-locale "boo" :xx)]

      (assertions
        "Returns nil if no locale is found"
        missing-xlation => nil
        "Loaded translation exists"
        xlation => {::i18n/locale       :es
                    ::i18n/translations {["" "It is {n,date}"]       "Es {n, date}"
                                         ["" "Hello, {name}"]        "Hola {name}"
                                         ["Gender abbreviation" "M"] "M"
                                         ["" "Hello"]                "Hola"}}))))

(defsc Child [this props]
  {:query         [:ui/checked?]
   :initial-state {:ui/checked? false}}
  (dom/div nil
    (dom/p nil (trf "Hello, {name}" {:name "Sam"}))
    (dom/p nil (trf "It is {n,date}" {:n (Date.)}))
    (dom/p nil (trc "Gender abbreviation" "M"))
    (tr "Hello")))

(def ui-child (prim/factory Child))

(defsc Root [this {:keys [child locale-selector]}]
  {:query         [{:locale-selector (prim/get-query i18n/LocaleSelector)}
                   {::i18n/current-locale (prim/get-query i18n/Locale)}
                   {:child (prim/get-query Child)}]
   :initial-state {:child                {}
                   ::i18n/current-locale {:locale :en :name "English" :translations {}}
                   :locale-selector      {:locales [{:locale :en :name "English"}
                                                    {:locale :es :name "Espanol"}
                                                    {:locale :de :name "Deutsch"}]}}}
  (dom/div nil
    (i18n/ui-locale-selector locale-selector)
    (ui-child child)))

(defn message-formatter [{:keys [::i18n/localized-format-string ::i18n/locale ::i18n/format-options]}]
  localized-format-string)

(specification "Locale override in SSR"
  (let [initial-tree     (prim/get-initial-state Root {})
        es-locale        (i18n/load-locale "fulcro/alpha" :es)
        tree-with-locale (assoc initial-tree ::i18n/current-locale es-locale)
        ui-root          (prim/factory Root)
        output-html      (i18n/with-locale message-formatter es-locale
                           (dom/render-to-str (ui-root tree-with-locale)))
        bad-locale-html  (i18n/with-locale message-formatter nil
                           (dom/render-to-str (ui-root initial-tree)))]
    (assertions
      "Renders properly even if the locale isn't set correctly"
      (str/includes? bad-locale-html "It is {n,date}") => true
      "Renders properly with the overridden locale"
      (str/includes? output-html "Es {n, date}") => true)))
