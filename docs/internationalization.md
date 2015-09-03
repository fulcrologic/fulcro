# Untangled Internationization

The internationalization support in Untangled is based on a number of tools to give a fully-functional, bi-directional
localization and internationalization solution that includes:

- GNU Gettext message support
   - Use plain strings in your UI
   - The plain strings are extracted
   - Standard GNU utilities are used to build translation files
   - Translations are complied to JS dependencies
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
     
## Extracting Strings for Translation

- Compile your app using :whitespace optimization only
- Run xgettext on the resulting .js file
- Send that .po file to the translator (or merge it with prior). See GNU Gettext for details

## Generating localized message files

- Convert the final translations (in .po format) to JavaScript using ...

## Install generated javascript translation code

## Set Locale at run-time (auto-loads translations if available)


