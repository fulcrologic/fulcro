(ns fulcro.localization
  (:require
    [fulcro.client.mutations :refer [defmutation]]
    [fulcro.client.logging :as log]
    #?(:cljs yahoo.intl-messageformat-with-locales)
    [fulcro.client.data-fetch :as df]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc]]))

(defsc Locale
  "Represents the data of a locale in app state. Normalized by locale ID."
  [this props]
  {:query         [::locale ::locale-name ::translations]
   :initial-state {::locale :param/locale ::locale-name :param/name ::translations :param/translations}
   :ident         [::locale-by-id ::locale]})

(defmutation translations-loaded
  "Post-mutation. Called after a successful load of a locale."
  [ignored]
  (action [{:keys [state reconciler]}]
    (swap! state dissoc ::translations)
    (when reconciler
      (prim/force-root-render! reconciler))))

(defn is-locale-loaded?
  "Returns true if the given locale is loaded in the given state map."
  [state-map locale]
  (boolean (get-in state-map [::locale-by-id locale ::translations] false)))

(defn ensure-locale-loaded!
  "Ensure that the given locale is loaded. Is a no-op if there are translations in app state for the given locale
  which is a string like \"es-MX\"."
  [reconciler locale]
  (let [state (prim/app-state reconciler)]
    (when-not (is-locale-loaded? @state locale)
      (df/load reconciler ::translations Locale {:params        {:locale locale}
                                                 :marker        false
                                                 :post-mutation `translations-loaded}))))
(defmutation change-locale
  "Mutation: Change the locale. The parameter should be a locale ID, which is a string like \"en\" or \"es-MX\"."
  [{:keys [locale]}]
  (action [{:keys [state reconciler]}]
    (ensure-locale-loaded! reconciler locale)
    (swap! state assoc ::current-locale (prim/get-ident Locale {::locale locale}))
    #?(:cljs (js/setTimeout #(prim/force-root-render! reconciler) 1)))
  (refresh [env]
    [::current-locale]))

(defn t
  "Translate a string in the context of the given component.

  The string may include ICU format placeholder, in which case the data for those placeholders
  should be passed in options:

  (t this \"Hello, {name}\" {:name \"Sam\"})

  options may include :fulcro.localization/context to give context to translators.
  "
  ([this string]
   (let [k           (str "|" string)
         translation (get-in (prim/shared this) [::translations k] string)]
     translation))
  ([this string {:keys [::context] :as options}]
   (let [k           (str context "|" string)
         locale      (get-in (prim/shared this) [::locale] "en")
         translation (get-in (prim/shared this) [::translations k] string)]
     (if (empty? (dissoc options ::context))
       translation
       #?(:clj  translation                                 ; FIXME: SSR trf
          :cljs (try
                  (let [;custom-formats (when @*custom-formats* (clj->js @*custom-formats*))
                        formatter (js/IntlMessageFormat. translation locale) #_(if custom-formats
                                                                                 (js/IntlMessageFormat. translation (current-locale) custom-formats)
                                                                                 (js/IntlMessageFormat. translation (current-locale)))]
                    (.format formatter (clj->js options)))
                  (catch :default e
                    (log/error "Unable to format trf output " e)
                    "???")))))))

(defsc LocaleSelector
  "A reusable locale selector. Generates a simple `dom/select` with CSS class fulcro$localization$locale_selector.

  Remember that for localization to work you *must* query for `:fulcro.localization/current-locale` in your root
  component with the query [{:fulcro.localization/current-locale (prim/get-query Locale)}]."
  [this {:keys [::available-locales ::current-locale]}]
  {:query         [{::available-locales (prim/get-query Locale)}
                   {[::current-locale '_] (prim/get-query Locale)}]
   :initial-state {::available-locales :param/locales}}
  (let [{:keys [::locale]} current-locale]
    (dom/select #js {:className "fulcro$localization$locale_selector"
                     :onChange  (fn [evt] #?(:cljs (prim/transact! this `[(change-locale {:locale ~(.. evt -target -value)})])))
                     :value     locale}
      (map-indexed
        (fn [i {:keys [::locale ::locale-name]}]
          (dom/option #js {:key i :value locale} locale-name))
        available-locales))))

(def ui-locale-selector (prim/factory LocaleSelector))
