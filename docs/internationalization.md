# Untangled Internationalization

The internationalization support in Untangled is based on a number of tools to give a fully-functional, bi-directional
localization and internationalization solution that includes:

- GNU Gettext message support
   - Use plain strings in your UI
   - The plain strings are extracted
   - Standard GNU utilities are used to build translation files
   - Translations are complied to cljs dependencies
- Extensions to Gettext support:
   - Formatted messages
     - Output of localized numbers, dates, currency symbols
- Support for output *and* input of dates, numbers, and currency strings
   - E.g. User can type 4.211,44 in one field and 03.11.2011 in another with German as the locale and you can easily 
   convert that to the javascript number 4211.44 and a Date object with November 3, 2011 in it. 
- Yahoo's FormatJS for output.
   - Augmented parsing to get the same for input
   
   
## Translating Messages (including numbers/dates/currencies)

Your component render can simply use one of the various output translation functions on strings. String constructions
that involve variables should be constructed with `trf`.


     (ns mine
        (:require untangled.i18n :refer-macros [tr trc trf])
        )
     
     (tr "Some message")
     (trf "You owe {amount, number, usd}" :amount 122.34)
     (let [filter "completed"] 
       (trf "Current filter: {filter, select, all {All} completed {Completed}}" :filter filter))

Internally, formatting uses the standard ICU message syntax provided by FormatJS.  This library includes number 
formatters for the following currencies: usd, euro, yen. These are easily extendable via TODO...

## Translating Date objects and raw numbers

You can use `trf` to format these; however, if you're just wanting a nice standard string form of a date or number 
without any fuss it may be more convenient to use these:


     (i18n/format-date :short date-object) ; 3/4/1998, 4.3.1998, etc
     (i18n/format-date :medium date-object) ; Mar 4, 1998
     (i18n/format-date :long date-object) ; March 4, 1998
     
     (i18n/format-number 44.6978) ; "44.6978" "44,6978", etc.
     
     (i18n/format-currency 44.6978) ; Truncates by locale: US: "$44.69" Japan: "¥44", etc.
     (i18n/format-rounded-currency 44.6978) ; Rounds by locale: US: "44.70" Japan: "¥45", etc.
     
## Parsing dates and numbers

At the time of this writing, HTML INPUT does not support internationalized input in any consistent, usable form. As 
a result it is necessary to normally just take text input and parse that string as a separate step. The Untangled
framework includes components for forms that can handle this complexity for you:


     ; app-state
       :comp
          :n (make-number-input)
          :start-date (make-date-input)
          
     ; rendering
     [data context] ; args to the render of :comp
     ; render the components with :changed event handlers to deal with the data as it changes
     (u/number-input :n context {:changed (fn [] (u/get-value (:n data))) })
     (u/date-input :start-date context { :changed (fn [] (u/get-value (:start-date data))) })
     
## Translation extraction and deployment

### Plugin Installation and Setup
Untangled ships with a leiningen plugin that conveniently:

- extracts strings into a messages template file (messages.pot)
- merges new strings from the template into existing locale-specific translation files (eg: ja_JP.po)
- generates cljs files from locale-specific translations and installs them into your project

The plugin is only supported on Unix/Linux systems.

### Install Dependencies

The i18n plugin requires that gettext tools are installed and available in your $PATH.
On MAC OS install via homebrew:

`brew install gettext`

`brew link --force gettext`

The link command is required because some software get confused if two of the same utilities are in the library path.

Also, make sure that the project you are going to leverage the gettext against has a path 'i18n/msgs`

### Configure Plugin

Add `[untangled "0.1.0-SNAPSHOT"]` to the `:plugins` list in `project.clj`.

The i18n plugin will look for configuration options at the `:untangled-i18n` key in your `project.clj`:

        :untangled-i18n {:default-locale        "en-US"
                         :translation-namespace survey.i18n}

`:default-locale` is the locale you would like your users to see initially, defaults to `"en-US"`

`:translation-namespace` is the clojure/clojurescript namespace in which the plugin will deploy translations and
supporting code files. The plugin will create a corresponding directory path if one does not exist. A new subdirectory
will be created after each `.` in the namespace.
                         
### Plugin Usage and Translator Workflow

Suppose that you have just finished an awesome new feature in your project. This feature has added new untranslated
strings to your UI, and you would like to have the new parts of your UI translated for international users. To extract
your new strings for translation, run this command from the root of your project.

`lein i18n extract-strings`

This will generate a new `messages.pot` in the `i18n/msgs` directory of your project. If you have existing translation
files in your project (eg: `i18n/msgs/fr_CA.po`), these files will be updated with your new untranslated strings. Any
existing translations in `fr_CA.po` will be preserved!

The updated `fr_CA.po` file now needs to be sent off to your human translator, who will see the new untranslated
strings in the file and produce the required translations. The translator will then send `fr_CA.po` file back to you,
and you will need to replace `i18n/msgs/fr_CA.po` with the new version provided by the translator. If you need to add a
new locale to the project (eg, we now want to add support for German), you will send the `i18n/msgs/messages.pot` file
to the German translator, and they will provide you with a `de.po` file which you will add to the `i18n/msgs` directory.

Now would be a good time to commit your new `*.po` files to version control.

We now want to convert `*.po` translation files into a format that your project can load at runtime when a user needs to
see translations in the UI. Run the following command from the root of your project to deploy new translations into your
project:

`lein i18n deploy-translations`

You now should be able to see the new translations in your app!


## Dynamic Translation Loading

Translations are dynamically loaded when the user requests a change to their locale. The leiningen plugin generates
clojurescript code to support this dynamic loading, but there some requirements your project must meet in order to
support this.

### :require Supporting Namespaces

Your `main` namespace should `:require` the `locales` and `default-locale` namespaces which were generated by the
i18n plugin, eg:

         (ns survey.main
           (:require [survey.core :as c]
                     survey.i18n.locales
                     survey.i18n.default-locale
                     [untangled.i18n.core :as i18n]))

### Configure :modules in cljsbuild

Your production cljsbuild configuration should contain a `:modules` entry which configures support for `goog.module`
to dynamically load JS modules on request. The i18n plugin will detect if your project is missing the `:modules` entry,
and print the suggested configuration. Note that the i18n plugin will not check the correctness of an existing `:modules`
config!

### Static Translation Loading in Dev Mode

When developing your project, you may want to test in other locales. Make sure your project has the following:

- `:require` each locale you wish to test in your `cljs.user` namespace, like so:

        (:require survey.i18n.en-US
                  survey.i18n.es-MX)
                  
- set a javascript variable called `i18nDevMode`, before your app `<div>` is loaded in index.html:

        <script>i18nDevMode = true;</script>

## Set Locale at Run-time

Call the `locales/set-locale` function that was generated by the i18n plugin, inside of a UI component:

        (c/ul {:className      "dropdown-menu"
               :aria-labeledby "language-dropdown"}
              (c/li {} (c/a {:href "#" :onClick #(lc/set-locale op "en-US")} "English"))
              (c/li {} (c/a {:href "#" :onClick #(lc/set-locale op "ja-JP")} "日本語"))
              (c/li {} (c/a {:href "#" :onClick #(lc/set-locale op "de")} "Deutsche"))
              (c/li {} (c/a {:href "#" :onClick #(lc/set-locale op "es-MX")} "Español")))
