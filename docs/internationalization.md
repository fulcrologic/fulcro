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
   
   
TODO: More docs
