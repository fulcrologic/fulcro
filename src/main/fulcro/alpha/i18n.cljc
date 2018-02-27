(ns fulcro.alpha.i18n
  "An i18n rewrite. Current considered ALPHA."
  #?(:cljs (:require-macros fulcro.alpha.i18n))
  (:require
    [fulcro.client.mutations :refer [defmutation]]
    [clojure.spec.alpha :as s]
    [fulcro.logging :as lg]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [clojure.string :as str]
    #?@(:clj ( [fulcro.gettext :as gt] [clojure.java.io :as io]))))

#?(:clj
   (defn load-locale
     "Load a po file. If po-dir is relative then it will come from CLASSPATH. If it is absolute it
     wil; come from the filesystem. The `locale` must be a keyword that matches an existing locale name in
     ll-CC format (e.g. `:en-US`).

     Returns a map keyed by locale keyword (e.g. :en-US) whose value is the correct data for client-side
     translations.
     "
     [po-dir locale]
     {:pre [(string? po-dir) (keyword? locale)]}
     (let [po-file      (str po-dir "/" (name locale))
           input        (if (str/starts-with? po-file "/")
                          (io/as-file po-file)
                          (io/resource po-file))
           translations (map gt/block->translation (gt/get-blocks input))]
       {:msgctxt "Abbreviation for Monday" :msgid "M" :msgstr ""}
       )))

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
  which is a keyword like :es-MX."
  [reconciler locale]
  (let [state (prim/app-state reconciler)]
    (when-not (is-locale-loaded? @state locale)
      (df/load reconciler ::translations Locale {:params        {:locale locale}
                                                 :marker        false
                                                 :post-mutation `translations-loaded}))))
(defmutation change-locale
  "Mutation: Change the locale. The parameter should be a locale ID, which is a keyword like :en or :es-MX."
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

  (t \"Hello, {name}\" {:name \"Sam\"})

  options may include ::i18n/context to give context to translators.
  "
  ([string]
   (let [k           ["" string]
         translation (get-in prim/*shared* [::translations k] string)]
     translation))
  ([string {:keys [::context] :as options}]
   (let [k           [(or context "") string]
         locale      (get-in prim/*shared* [::locale])
         translation (get-in prim/*shared* [::translations k] string)
         formatter   (get prim/*shared* ::message-formatter (fn [{:keys [::localized-format-string]}] localized-format-string))]
     (if (empty? (dissoc options ::context))
       translation
       #?(:clj  translation                                 ; FIXME: SSR trf
          :cljs (try
                  (formatter {::localized-format-string translation ::locale locale ::format-options options})
                  (catch :default e
                    (lg/error "Unable to format output " e)
                    "???")))))))

(defsc LocaleSelector
  "A reusable locale selector. Generates a simple `dom/select` with CSS class fulcro$i18n$locale_selector.

  Remember that for localization to work you *must* query for `::i18n/current-locale` in your root
  component with the query [{::i18n/current-locale (prim/get-query Locale)}]."
  [this {:keys [::available-locales ::current-locale]}]
  {:query         [{::available-locales (prim/get-query Locale)}
                   {[::current-locale '_] (prim/get-query Locale)}]
   :initial-state {::available-locales :param/locales}}
  (let [{:keys [::locale]} current-locale
        locale-kw (fn [l] (-> l (str/replace #":" "") keyword))]
    (dom/select #js {:className "fulcro$i18n$locale_selector"
                     :onChange  (fn [evt] #?(:cljs (prim/transact! this `[(change-locale {:locale ~(locale-kw (.. evt -target -value))})])))
                     :value     locale}
      (map-indexed
        (fn [i {:keys [::locale ::locale-name]}]
          (dom/option #js {:key i :value locale} locale-name))
        available-locales))))

(def ui-locale-selector (prim/factory LocaleSelector))


