(ns fulcro.i18n
  " Internationalization support GNU gettext-style.

  This support allows translations to stay in raw gettext PO file format, and be served as normal API data loads.

  To use this support:

  1. Use `tr`, `trf`, `trc`, etc. to embed messages in your UI.
  2. Embed a locale selector, such as the one provided.
  3. Configure your server to serve locales.
  4. Compile the source of your application with whitespace optimizations.
  5. Use `xgettext` (GNU CLI utility) to extract the strings from the js output of (4).
        xgettext --from-code=UTF-8 --debug -k -ktr_alpha:1 -ktrc_alpha:1c,2 -ktrf_alpha:1 -o messages.pot application.js
  6. Have translators generate PO files for each locale you desire, and place those where your server can serve them.

  See the Developer's Guide for more details."
  #?(:cljs (:require-macros fulcro.i18n))
  (:require
    [fulcro.client.mutations :refer [defmutation]]
    [clojure.spec.alpha :as s]
    [fulcro.logging :as lg]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [clojure.string :as str]
    #?@(:clj (
    [fulcro.gettext :as gt]
    [clojure.java.io :as io]))))

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
     (let [po-file      (str po-dir "/" (name locale) ".po")
           input        (if (str/starts-with? po-file "/")
                          (io/as-file po-file)
                          (io/resource po-file))
           translations (try
                          (map gt/block->translation (gt/get-blocks input))
                          (catch Throwable e
                            (lg/error "Failed to load translations for locale " locale po-file e)
                            nil))]
       (when translations
         {::locale       locale
          ::translations (into {} (map (fn [t] [[(or (:msgctxt t) "") (:msgid t)] (:msgstr t)])) translations)}))))

(defsc Locale
  "Represents the data of a locale in app state. Normalized by locale ID."
  [this props]
  {:query         [::locale :ui/locale-name ::translations]
   :initial-state {::locale :param/locale :ui/locale-name :param/name ::translations :param/translations}
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

  This is a general-purpose function for doing everything that tr, trc, and trf do; however, it does not allow for
  source-level string extraction with GNU gettext. It is recommended that you use
  use `tr`, `trc`, and such instead.

  Options is sent to the configured formatter, and may also include ::i18n/context to represent translation context.
  "
  ([string]
   (let [k           ["" string]
         translation (get-in prim/*shared* [::translations k] string)]
     (if (= "" translation) string translation)))
  ([string {:keys [::context] :as options}]
   (let [k           [(or context "") string]
         locale      (get-in prim/*shared* [::locale] :en)  ; some locale needed or formatter might crash
         entry       (get-in prim/*shared* [::translations k] string)
         translation (if (= "" entry) string entry)
         formatter   (get prim/*shared* ::message-formatter (fn [{:keys [::localized-format-string]}] localized-format-string))]
     (if (empty? (dissoc options ::context))
       translation
       (try
         (formatter {::localized-format-string translation ::locale locale ::format-options options})
         (catch #?(:cljs :default :clj Throwable) e
           (lg/error "Unable to format output " e)
           "???"))))))

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
        (fn [i {:keys [::locale :ui/locale-name]}]
          (dom/option #js {:key i :value locale} locale-name))
        available-locales))))

(def ui-locale-selector (prim/factory LocaleSelector))


#?(:clj
   (defn tr-ssr [msg] (t msg))
   :cljs
   (set! js/tr (fn tr [msg] (t msg))))

#?(:clj
   (defn trc-ssr [ctxt msg] (t msg {::context ctxt}))
   :cljs
   (set! js/trc (fn [ctxt msg] (t msg {::context ctxt}))))

#?(:clj
   (defn trf-ssr
     [fmt & rawargs]
     (let [args   (if (and (= 1 (count rawargs)) (map? (first rawargs)))
                    (first rawargs)
                    (into {} (mapv vec (partition 2 rawargs))))
           argmap (into {} (map (fn [[k v]] [(name k) v]) args))]
       (t fmt argmap)))
   :cljs
   (set! js/trf
     (fn trf [fmt & args]
       (let [argmap (if (and (= 1 (count args)) (map? (first args)))
                      (first args)
                      (into {} (mapv vec (partition 2 args))))]
         (t fmt argmap)))))

#?(:clj
   (defmacro tr-unsafe
     "Look up the given message. Using this function without a literal string will make string extraction from source
     impossible. This means you have to use some other mechanism to make sure the string ends up in translation
     files (such as manually calling tr on the various raw string values elsewhere in your program)."
     [msg]
     (if (:ns &env) `(js/tr ~msg) `(tr-ssr ~msg))))

#?(:clj
   (defmacro tr
     "Translate the given literal string. The argument MUST be a literal string so that it can be properly extracted
     for use in gettext message files as the message key. This macro throws a detailed assertion error if you
     violate this restriction. See trf for generating translations that require formatting (e.g. construction from
     variables)."
     [msg]
     (let [{:keys [line]} (meta &form)
           msg (if (string? msg)
                 msg
                 (str "ERROR: tr requires a literal string on line " line " in " (str *ns*)))]
       (if (:ns &env) `(js/tr ~msg) `(tr-ssr ~msg)))))

#?(:clj
   (defmacro trc
     "Same as tr, but include a context message to the translator. This is recommended when asking for a
     translation to something vague.

     For example:

            (tr \"M\")

     is the same as asking a translator to translate the letter 'M'.

     Using:

            (trc \"abbreviation for male gender\" \"M\")

     lets the translator know what you want. Of course, the msg key is the default language value (US English)
     "
     [context msg]
     (let [{:keys [line]} (meta &form)
           [context msg] (if (and (string? context) (string? msg))
                           [context msg]
                           ["" (str "ERROR: trc requires literal strings on line " line " in " (str *ns*))])]
       (if (:ns &env) `(js/trc ~context ~msg) `(trc-ssr ~context ~msg)))))

#?(:clj
   (defmacro trc-unsafe
     "Same as trc, but does not check for literal strings for arguments. THIS MEANS strings extraction from source for
      these values will not be possible, and you will have to manually ensure they are included in translations."
     [context msg]
     (if (:ns &env) `(js/trc ~context ~msg) `(trc-ssr ~context ~msg))))

#?(:clj
   (defmacro trf
     "Translate a format string, then use it to format a message with the given arguments. The format MUST be a literal
     string for extraction by gettext. The arguments should a map of keyword/value pairs that will match the embedded
     items to format.

     (trf \"{name} owes {amount, currency)\" {:name who :amount amt})
     "
     [format & args]
     (let [{:keys [line]} (meta &form)
           [format args] (if (string? format)
                           [format args]
                           [(str "ERROR: trf requires a literal string on line " line " in " (str *ns*)) []])]
       (if (:ns &env) `(js/trf ~format ~@args) `(trf-ssr ~format ~@args)))))

#?(:clj
   (defmacro with-locale
     "Establish a message formatting and locale context for rendering. Can be used on the client or server to
      force a given locale and message formatting context for the enclosed elements.

      It is typically used for server-side rendering like this:

      ```
      (defn message-formatter ...) ; a server-side message formatter, like IBM's ICU library

      (defn generate-index-html [state-db app-html]
        (let [initial-state-script (ssr/initial-state->script-tag state-db)]
          (str \"<html><head>\" initial-state-script \"</head><body><div id='app'>\" app-html \"</div></body></html>\")))

      (let [initial-tree     (prim/get-initial-state Root {})
            es-locale        (i18n/load-locale \"my-po-files\" :es)
            tree-with-locale (assoc initial-tree ::i18n/current-locale es-locale)
            initial-db       (ssr/build-initial-state tree-with-locale Root) ; embed this as initial state in the HTML
            ui-root          (prim/factory Root)]
        (generate-index-html initial-db  ; some function that generates the complete wrapped HTML. See server-side rendering for more detail
          (i18n/with-locale message-formatter es-locale
            (dom/render-to-str (ui-root tree-with-locale)))))
      ```

      Note: `locale` can technically contain anything that the given UI needs in `shared` props, since this macro will
      completely override shared props with the given information.
      "
     [message-formatter locale & render-body]
     `(let [shared-props# (merge {:fulcro.i18n/message-formatter ~message-formatter} ~locale)]
        (binding [fulcro.client.primitives/*shared* shared-props#]
          ~@render-body))))
