# Untangled Internationization

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

### leiningen plugin installation
Untangled ships with a leiningen plugin that conveniently:

- extracts strings into a messages template file (messages.pot)
- merges new strings from the template into existing locale-specific translation files (eg: ja_JP.po)
- generates cljs files from locale-specific translations and installs them into your project

The leiningen plugin must be configured in your project.clj file.  Simply add `:plugins [untangled "0.1.0-SNAPSHOT"]` to the
project.clj.


### leiningen plugin usage and translator workflow

Suppose that you have just finished an awesome new feature in your project. This feature has added new untranslated
strings to your UI, and you would like to have the new parts of your UI translated for international users. To extract
your new strings for translation, run this command from the root of your project.

`lein i18n extract`

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

`lein i18n deploy`

You now should be able to see the new translations in your app!


## Set Locale at run-time (auto-loads translations if available)
