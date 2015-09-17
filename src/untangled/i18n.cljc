(ns untangled.i18n)

#?(:cljs (set! js/tr identity))
#?(:cljs (set! js/trc (fn [ctxt msg] msg)))
#?(:cljs
   (set! js/trf
         (fn [fmt & {:keys [] :as argmap}]
           (let [formatter (js/IntlMessageFormat. fmt "en-US")]
             (.format formatter (clj->js argmap))
             ))))

#?(:cljs (defn format-date
           "Format a date with an optional style. The default style is :short.
           
           Style can be one of:
           
           - :short E.g. 3/4/2015
           - :medium E.g. Mar 4, 2015
           - :long E.g. March 4, 2015
           "
           ([date style] (.toLocaleDateString date))
           ([date] (.toLocaleDateString date))
           ))
#?(:cljs (defn format-number
           "Format a number with locale-specific separators (grouping digits, and correct marker for decimal point)"
           [number] number))
#?(:cljs (defn format-currency
           "Format a number as a currency (dropping digits that are insignificant in the current currency.)"
           [number] (.toPrecision number 2)))
#?(:cljs (defn format-rounded-currency
           "Round and format a number as a currency, according to the current currency's rules."
           [number] (.toPrecision (/ (.round (* 100 number)) 100.0) 2)))

#?(:clj (defmacro tr
          "Translate the given literal string. The argument MUST be a literal string so that it can be properly extracted
          for use in gettext message files as the message key. This macro throws a detailed assertion error if you
          violate this restriction. See trf for generating translations that require formatting (e.g. construction from
          variables)."
          [msg]
          (assert (string? msg) (str "In call to tr(" msg "). Argument MUST be a literal string, not a symbol or expression. Use trf for formatting."))
          `(js/tr ~msg)))

#?(:clj (defmacro trc
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
          (assert (and (string? msg) (string? context)) (str "In call to trc(" context msg "). Arguments MUST be literal strings."))
          `(js/trc ~context ~msg)))

#?(:clj (defmacro trf
          "Translate a format string, then use it to format a message with the given arguments. The format MUST be a literal
          string for extraction by gettext. The arguments should be keyword/value pairs that will match the embedded
          items to format.
          
          (trf \"{name} owes {amount, currency)\" :name who :amount amt)
          
          The format string is an ICU message format. See FormatJS for details.
          "
          [format & args]
          (assert (string? format) (str "Message format in call to trf(" format args ") MUST be literal string (arguments can be variables)."))
          `(js/trf ~format ~@args)))
