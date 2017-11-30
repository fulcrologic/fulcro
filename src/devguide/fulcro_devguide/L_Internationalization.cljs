(ns fulcro-devguide.L-Internationalization
  (:require-macros [cljs.test :refer [is]])
  (:require [devcards.core :as dc :include-macros true :refer-macros [defcard-doc dom-node]]
            [fulcro.i18n :as ic :refer-macros [tr trc trf]]
            yahoo.intl-messageformat-with-locales
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.core :as fc]
            [cljs.reader :as r]
            [fulcro.client.impl.parser :as p]
            [fulcro.client.dom :as dom]
            [fulcro.client.primitives :as prim :refer [defui defsc]]
            [fulcro.client.mutations :as m]))

(reset! ic/*loaded-translations* {"es" {"|This is a test" "Spanish for 'this is a test'"
                                        "|Hi, {name}"     "Ola, {name}"}})

(defn locale-switcher [comp]
  (dom/div nil
    (dom/button #js {:onClick #(prim/transact! comp `[(m/change-locale {:lang "en"}) :ui/locale])} "en")
    (dom/button #js {:onClick #(prim/transact! comp `[(m/change-locale {:lang "es"}) :ui/locale])} "es")))

(defui Test
  static prim/IQuery
  (query [this] [:ui/react-key :ui/locale])
  Object
  (render [this]
    (let [{:keys [ui/react-key ui/locale]} (prim/props this)]
      (dom/div #js {:key react-key}
        (locale-switcher this)
        (dom/span nil (str "Locale: " locale))
        (dom/br nil)
        (tr "This is a test")))))

(defcard-doc
  "# Internationalization

  Fulcro combines together a few tools and libraries to give a consistent and easy-to-use method of internationalizing
  you application. The approach includes the following features:

  - A global setting for the *current* application locale.
  - The ability to write UI strings in a default language/locale in the code. These are the defaults that are shown on
  the UI if no translation is available.
  - The ability to extract these UI strings for translation in POT files (GNU gettext-style)
      - The translator can use tools like POEdit to generate translation files
  - The ability to convert the translation files into Clojurescript
  - The ability to code-split your locales so they can be dynamically loaded when the locale is changed (Clojurescript 1.9.905+ and Fulcro 1.0.0-beta9+).
  - The ability to format messages (using Yahoo's formatJS library), including very configurable plural support

  Fulcro leverages the GNU `xgettext` tool, and Yahoo's formatJS library (which in turn leverages browser support
  for locales) to do most of the heavy lifting.

  ## Annotating Strings

  The simplest thing to do is marking the UI strings that need translation. This is done with the `tr` macro:

  ```
  (namespace boo
    (:require [fulcro.i18n :refer [tr]]))

  ...
  (tr \"This is a test\")
  ...
  ```

  By default (you have not created any translations) a call to `tr` will simply return the parameter unaltered.
  ")

(defcard-fulcro sample-translation
  "This card shows the output of a call to `tr`. Note that this page has translations for the string, so if you
  change the locale this string will change."
  Test)

(defcard-doc "
  ## Changing the Locale

  The locale of the current browser tab can be changed through the built-in mutation `fulcro.client.mutations/change-locale` with a `:lang`
  parameter (which can use the ISO standard two-letter language with an optional country code):

  ```
  (prim/transact! reconciler '[(fulcro.client.mutations/change-locale {:lang :es})])
  ; or if you've aliased mutations to m:
  (prim/transact! reconciler `[(m/change-locale {:lang :es})])
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
  static prim/InitialAppState
  (initial-state [clz p] {:ui/label "Your Name"})
  static prim/Ident
  (ident [this props] [:components :ui])
  static prim/IQuery
  (query [this] [:ui/label])
  Object
  (render [this]
    (let [{:keys [ui/label]} (prim/props this)]
      (dom/div nil
        (locale-switcher this)
        (dom/input #js {:value label :onChange #(m/set-string! this :ui/label :event %)})
        (trf "Hi, {name}" :name label)
        (dom/br nil)
        (trf "N: {n, number} ({m, date, long})" :n 10229 :m (new js/Date))
        (dom/br nil)))))

(def ui-format (prim/factory Format))

(defui Root2
  static prim/InitialAppState
  (initial-state [clz p] {:format (prim/get-initial-state Format {})})
  static prim/IQuery
  (query [this] [:ui/react-key :ui/locale {:format (prim/get-query Format)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key ui/locale format]} (prim/props this)]
      (dom/div #js {:key react-key}
        (dom/span nil (str "Locale: " locale))
        (dom/br nil)
        (ui-format format)))))


(defcard-fulcro formatted-examples
  "This card shows the results of some formatted and translated strings"
  Root2)

(defcard-doc
  "
  ## Manually Installing Translations (see automation below as well)

  The format for translations is rather simple, so you can hand-code translations if you care to. The
  format of a translation entry key is \"context|msgkey\". So, for example the key for a `(tr \"Hi\")`
  is \"|Hi\" and the key for `(trc \"male\" \"M\")` is \"male|M\".

  Installing a translation map can be done as:

  ```
  (swap! fulcro.i18n/loaded-translations assoc \"es\" {\"|This is a test\" \"Spanish for 'this is a test'\"})
  ```

  in other words there is a global atom that holds the currently-loaded translations as a map, keyed by locale. The
  map entries are the above-mentioned translation entry keys and the desired translation.

  ## Using Tools to Generate Translation Files

  ### Extracting Strings for Translation

  String extraction is done via the following process:

  The application is compiled using `:whitespace` optimization. This provides a single Javascript file. The GNU
  utility xgettext can then be used to extract the strings. You can use something like Home Brew to install this
  utility.

  ```
  xgettext --from-code=UTF-8 --debug -k -ktr:1 -ktrc:1c,2 -ktrf:1 -o messages.pot compiled.js
  ```

  If you have existing translations, you'd then merge this POT file into them. If you needed to make a new locale, you'd
  use the GNU `msginit` command.

  Fulcro comes with a function to do both the extraction and merging from a REPL: `(fulcro.gettext { options })`.

  ```
  $ lein run -m clojure.main
  user=> (require 'fulcro.gettext)
  user=> (fulcro.gettext/extract-strings {:js-path \"i18n/i18n.js\" :po \"i18n\"})
  ```

  The `js-path` is the path to the whitespace-optimized js version of your app. The `po` is the folder where your
  POT and PO files live.

  NOTE: This task will automatically merge the generated `.pot` file into any pre-existing `.po` files as a final step,
  so updating translations will just involve sending off the locale-specific `.po` files.

  ### Translating Strings

  Once you've extracted the strings to a POT file you may translate them as you would any other gettext app. We
  recommend the GUI program POEdit. You should end up with a number of translations in files that you place
  in files like `i18n/es.po`, where `es` is the locale of the translation.
  Generally you'll check your `.po` files into source control in order to keep track of the translations you've already done,
  since the overall process allows carrying over old translations to newly extracted files.

  ## Generating Clojure Versions of the Translations

  Again, a function is provided for you that can be run from the REPL:

  ```
  (fulcro.gettext/deploy-translations {:src \"src/main\" :po \"i18n\" :as-modules? true})
  ```

  `src` is your target source folder, and `:po` is where
  you keep your PO files. This step will generate one cljc file for each PO file. If you supply `:as-modules?` then
  your translations will support being dynamically loaded by `cljs-loader` (as of beta9).
  Translations will be output to the `translations` package in namespaces that match the locale name.

  ### Using the Generated Translation Code

  If you are using static translations (that all get loaded at once), then your client startup should just require all of the locales so they'll get loaded. That's it!

  If you place each locale into a module in Clojurescript 1.9.0-905+ then you can use your locales as loadable modules. The
  `change-locale` mutation will automatically trigger loads if needed.
  ")



