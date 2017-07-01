(ns untangled-devguide.L-Internationalization
  (:require-macros [cljs.test :refer [is]]
                   [untangled-devguide.tutmacros :refer [untangled-app]])
  (:require [devcards.core :as dc :include-macros true :refer-macros [defcard defcard-doc dom-node]]
            [untangled.i18n :refer-macros [tr trc trf]]
            yahoo.intl-messageformat-with-locales
            [untangled.client.cards :refer [untangled-app]]
            [untangled.client.core :as uc]
            [cljs.reader :as r]
            [om.next.impl.parser :as p]
            [om.dom :as dom]
            [untangled.i18n :as ic]
            [om.next :as om :refer [defui]]
            [untangled.client.mutations :as m]))

(reset! ic/*loaded-translations* {"es" {"|This is a test" "Spanish for 'this is a test'"
                                        "|Hi, {name}"     "Ola, {name}"}})

(defn locale-switcher [comp]
  (dom/div nil
    (dom/button #js {:onClick #(om/transact! comp `[(m/change-locale {:lang "en"}) :ui/locale])} "en")
    (dom/button #js {:onClick #(om/transact! comp `[(m/change-locale {:lang "es"}) :ui/locale])} "es")))

(defui Test
  static om/IQuery
  (query [this] [:ui/react-key :ui/locale])
  Object
  (render [this]
    (let [{:keys [ui/react-key ui/locale]} (om/props this)]
      (dom/div #js {:key react-key}
        (locale-switcher this)
        (dom/span nil "Locale: " locale)
        (dom/br nil)
        (tr "This is a test")))))

(defcard-doc
  "# Internationalization

  Untangled combines together a few tools and libraries to give a consistent and easy-to-use method of internationalizing
  you application. The approach includes the following features:

  - A global setting for the *current* application locale.
  - The ability to write UI strings in a default language/locale in the code. These are the defaults that are shown on
  the UI if no translation is available.
  - The ability to extract these UI strings for translation in POT files (GNU gettext-style)
      - The translator can use tools like POEdit to generate translation files
  - The ability to convert the translation files into Clojurescript as modules that can be dynamically loaded when
    the locale is changed.
  - The ability to format messages (using Yahoo's formatJS library), including very configurable plural support

  Untangled leverages the GNU `xgettext` tool, and Yahoo's formatJS library (which in turn leverages browser support
  for locales) to do most of the heavy lifting.

  ## Annotating Strings

  The simplest thing to do is marking the UI strings that need translation. This is done with the `tr` macro:

  ```
  (namespace boo
    (:require [untangled.i18n :refer [tr]]))

  ...
  (tr \"This is a test\")
  ...
  ```

  By default (you have not created any translations) a call to `tr` will simply return the parameter unaltered.
  ")

(defcard sample-translation
  "This card shows the output of a call to `tr`. Note that this page has translations for the string, so if you
  change the locale this string will change."
  (untangled-app Test))

(defcard-doc "
  ## Changing the Locale

  The locale of the current browser tab can be changed through the built-in mutation `untangled.client.mutations/change-locale` with a `:lang`
  parameter (which can use the ISO standard two-letter language with an optional country code):

  ```
  (om/transact! reconciler '[(untangled.client.mutations/change-locale {:lang \"en-US\"})])
  ```

  The rendering functions will search for a translation in that language and country, fall back to the language if
  no country-specific entries exist, and will fall back to the default language (the one in the UI code) if no
  translation is found.

  ## Resolving Ambiguity

  It is quite common for a string to be ambiguous to the developer or translator. For example the abbreviation for 'male'
  might be 'M' in English, but the letter itself doesn't provide enough information for a translator to know what they
  are doing. A call to `trc` (translate with context) resolves this by including a context string as part of the
  internal lookup key and as a comment to the translator:

  ```
  (trc \"Abbreviation for male\" \"M\")
  ```

  ## Formatting

  `trf` (translate with format) accepts a format string (see the Yahoo FormatJS library) and any additional arguments
  that should be placed in the string. This function handles formatting of numbers, percentages, plurals, etc.

  Any named parameter that you use in the format string must have a corresponding named parameter in the call:

  ```
  (trf \"{a}, {b}, and {c}\" :a 1 :b 2 :c 3) ; => 1, 2, and 3
  ```

  If the input parameter is needs to be further localized you may include a variety of formatting types (which
  are extensible):

  ```
  (trf \"N: {n, number} ({m, date, long})\" :n 10229 :m (new js/Date))
  ```

  See the formatJS documentation for further details.
")

(defui Format
  static uc/InitialAppState
  (initial-state [clz p] {:ui/label "Your Name"})
  static om/Ident
  (ident [this props] [:components :ui])
  static om/IQuery
  (query [this] [:ui/label])
  Object
  (render [this]
    (let [{:keys [ui/label]} (om/props this)]
      (dom/div nil
        (locale-switcher this)
        (dom/input #js {:value label :onChange #(m/set-string! this :ui/label :event %)})
        (trf "Hi, {name}" :name label)
        (dom/br nil)
        (trf "N: {n, number} ({m, date, long})" :n 10229 :m (new js/Date))
        (dom/br nil)))))

(def ui-format (om/factory Format))

(defui Root2
  static uc/InitialAppState
  (initial-state [clz p] {:format (uc/initial-state Format {})})
  static om/IQuery
  (query [this] [:ui/react-key :ui/locale {:format (om/get-query Format)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key ui/locale format]} (om/props this)]
      (dom/div #js {:key react-key}
        (dom/span nil "Locale: " locale)
        (dom/br nil)
        (ui-format format)))))


(defcard formatted-examples
  "This card shows the results of some formatted and translated strings"
  (untangled-app Root2))

(defcard-doc
  "
  ## Manually Installing Translations (NOT recommended)

  The format for translations is rather simple, so you can hand-code translations if you care to. The
  format of a translation entry key is \"context|msgkey\". So, for example the key for a `(tr \"Hi\")`
  is \"|Hi\" and the key for `(trc \"male\" \"M\")` is \"male|M\".

  Installing a translation map can be done as:

  ```
  (swap! untangled.i18n.core/*loaded-translations* assoc \"es\" {\"|This is a test\" \"Spanish for 'this is a test'\"})
  ```

  in other words there is a global atom that holds the currently-loaded translations as a map, keyed by locale. The
  map entries are the abovementioned translation entry keys and the desired translation.

  ## Using Tools to Generate Translation Files

  ### Extracting Strings for Translation

  String extraction is done via the following process:

  The application is compiled using `:whitespace` optimization. This provides a single Javascript file. The GNU
  utility xgettext can then be used to extract the strings. You can use something like Home Brew to install this
  utility.

  ```
  xgettext --from-code=UTF-8 --debug -k -ktr:1 -ktrc:1c,2 -ktrf:1 -o messages.pot compiled.js
  ```

  A leiningen plugin exists for doing this for you:

  ```
  (defproject boo \"0.1.0-SNAPSHOT\"
    ...
    :plugins [navis/untangled-lein-i18n \"0.2.0\"]

    :untangled-i18n {:default-locale        \"en\" ;; the default locale of your app
                     :translation-namespace \"app.i18n\" ;; the namespace for generating cljs translations
                     :source-folder         \"src\" ;; the target source folder for generated code
                     :translation-build     \"i18n\" ;; The name of the cljsbuild to compile your code that has tr calls
                     :po-files              \"msgs\" ;; The folder where you want to store gettext files  (.po/.pot)
                     :production-build      \"prod\"} ;; The name of your production build

    :cljsbuild {:builds [{:id           \"i18n\"
                          :source-paths [\"src\"]
                          :compiler     {:output-to     \"i18n/out/compiled.js\"
                                         :main          entry-point
                                         :optimizations :whitespace}}
                         {:id \"prod\"
                          :source-paths [\"src\"]
                          :compiler {:asset-path    \"js\"
                                     :output-dir \"resources/public/js\"
                                     :main       core
                                     :output-to  \"resources/public/js/main.js\"
                                     :optimizations :advanced
                                     :source-map    true}}]})
  ```

  ```
  lein i18n extract-strings
  ```

  should run a cljs build of your i18n build and then generate the file `msgs/messages.pot`.

  NOTE: This task will automatically merge the generated `.pot` file into any pre-existing `.po` files as a final step,
  so updating translations will just involve sending off the locale-specific `.po` files.

  ### Translating Strings

  Once you've extracted the strings to a POT file you may translate them as you would any other gettext app. We
  recommend the GUI program POEdit. You should end up with a number of translations in files that you place
  in files like `msgs/es.po`, where `es` is the locale of the translation. Note that the extract-strings lein task
  will update these files with new (needed) translations for you. Generally you'll check your `.po` files into source
  control in order to keep track of the translations you've already done.

  ## Generating Clojurescript Versions of the Translations

  If you're using the i18n lein plugin, then there are two possible way to generate translation code: as loadable modules,
  and as part of the final compiled application. If you have a lot of strings and locales it can save some initial start
  time to use modules, at the expense of a more complicated project configuration.

  The i18n plugin command is simple enough:

  ```
  lein i18n deploy-translations
  ```

  and the overall configuration (without modules) is shown in the section on extracting the strings.

  If you want to use modules, there are a few more steps:

  1. Ensure you do *not* require any of your specific locales in any source file.
  2. Include a module definition in your production cljs build for every locale you want to make dynamically
  loadable. Be sure to use the locale name as the (keyword) key of the module.

  Something like the following should work:

  ```
  (defproject boo \"0.1.0-SNAPSHOT\"
    ...
    :plugins [navis/untangled-lein-i18n \"0.2.0\"]

    :untangled-i18n {:default-locale        \"en\" ;; the default locale of your app
                     :translation-namespace \"app.i18n\" ;; the namespace for generating cljs translations
                     :source-folder         \"src\" ;; the target source folder for generated code
                     :translation-build     \"i18n\" ;; The name of the cljsbuild to compile your code that has tr calls
                     :po-files              \"msgs\" ;; The folder where you want to store gettext files  (.po/.pot)
                     :production-build      \"prod\"} ;; The name of your production build

    :cljsbuild {:builds [{:id           \"i18n\"
                          :source-paths [\"src\"]
                          :compiler     {:output-to     \"i18n/out/compiled.js\"
                                         :main          entry-point
                                         :optimizations :whitespace}}
                         {:id \"prod\"
                          :source-paths [\"src\"]
                          :compiler {:asset-path    \"js\"
                                     :output-dir \"resources/public/js\"
                                     :optimizations :advanced
                                     :source-map    true
                                     :modules       {;; The main program
                                                     :cljs-base {:output-to \"resources/public/js/main.js \"}
                                                     ;; One entry for each locale
                                                     :de        {:output-to \"resources/public/js/de.js \" :entries #{\"app.i18n.de \"}}
                                                     :es        {:output-to \"resources/public/js/es.js \" :entries #{\"app.i18n.es \"}}}}}]})
  ```

  ### Using the Generated Translation Code

  The generated source will be in the namespace you configure in your project file (e.g. `app.i18n`). The generated
  code will include a `locales.cljs` file. You should require this file and use the `set-locale` function within it.
  This function will be set up to automatically load any dynamic locale modules (assuming you configured them correctly).

  If you didn't use modules, then no dynamic loading will be attempted.

  ```
  (ns some.ui
    (:require [app.i18n.locales :as l]))

  ...
  (l/set-locale \"es\") ; change the UI locale, possibly triggering a dynamic module load.
  ```
  ")



