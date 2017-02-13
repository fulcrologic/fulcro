(ns untangled.i18n
  #?(:cljs (:require-macros untangled.i18n))
  (:require
    #?(:clj js)
            [untangled.i18n.core :as ic]
            [untangled.client.logging :as log]
    #?(:cljs yahoo.intl-messageformat-with-locales)))

(defn current-locale [] @ic/*current-locale*)

(defn translations-for-locale [] (get @ic/*loaded-translations* (current-locale)))

;; This set of constructions probably looks pretty screwy. In order for xgettext to work right, it
;; must see `tr("hello")` in the output JS, but by default the compiler outputs a call(tr, args)
;; construction. By explicitly setting (and using) a Javascript function we don't have to
;; worry about compiler options for static calls.

;; The other thing we're doing is wrapping that in a macro. The macro serves two purposes: One
;; it makes better syntatic sugar than having to type `(js/tr ...)`, but the real consideration
;; is that we want `tr` to fail to compile if you use it with a variable. This is another important
;; consideration for ensuring that translations can be extracted. The `tr-unsafe` macro exists
;; for cases where you must have logic invoved, but lets you know that you must have some other
;; way of ensuring those translations make it into your final product.

#?(:cljs
   (set! js/tr
     (fn [msg]
       (let [msg-key      (str "|" msg)
             translations (translations-for-locale)
             translation  (get translations msg-key msg)]
         translation))))

#?(:cljs
   (set! js/trc
     (fn [ctxt msg]
       (let [msg-key      (str ctxt "|" msg)
             translations (translations-for-locale)
             translation  (get translations msg-key msg)]
         translation))))

#?(:cljs
   (set! js/trf
     (fn [fmt & {:keys [] :as argmap}]
       (try
         (let [msg-key      (str "|" fmt)
               translations (translations-for-locale)
               translation  (get translations msg-key fmt)
               formatter    (js/IntlMessageFormat. translation (current-locale))]
           (.format formatter (clj->js argmap)))
         (catch :default e (log/error "Failed to format " fmt " args: " argmap " exception: " e)
                           "???")))))

#?(:clj
   (defmacro tr
     "Translate the given literal string. The argument MUST be a literal string so that it can be properly extracted
     for use in gettext message files as the message key. This macro throws a detailed assertion error if you
     violate this restriction. See trf for generating translations that require formatting (e.g. construction from
     variables)."
     [msg]
     (assert (string? msg) (str "In call to tr(" msg "). Argument MUST be a literal string, not a symbol or expression. Use trf for formatting."))
     `(js/tr ~msg)))

#?(:clj
   (defmacro tr-unsafe
     "Look up the given message. UNSAFE: you can use a variable with this, and thus string extraction will NOT
     happen for you. This means you have to use some other mechanism to make sure the string ends up in translation
     files (such as manually calling tr on the various raw string values elsewhere in your program)"
     [msg]
     `(js/tr ~msg)))

#?(:clj
   (defmacro trlambda
     "Translate the given literal string. The argument MUST be a literal string so that it can be properly extracted
     for use in gettext message files as the message key. This macro throws a detailed assertion error if you
     violate this restriction. See trf for generating translations that require formatting (e.g. construction from
     variables)."
     [msg]
     (assert (string? msg) (str "In call to tr(" msg "). Argument MUST be a literal string, not a symbol or expression. Use trf for formatting."))
     `#(js/tr ~msg)))

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
     (assert (and (string? msg) (string? context)) (str "In call to trc(" context msg "). Arguments MUST be literal strings."))
     `(js/trc ~context ~msg)))

#?(:clj
   (defmacro trf
     "Translate a format string, then use it to format a message with the given arguments. The format MUST be a literal
     string for extraction by gettext. The arguments should be keyword/value pairs that will match the embedded
     items to format.

     (trf \"{name} owes {amount, currency)\" :name who :amount amt)

     The format string is an ICU message format. See FormatJS for details.
     "
     [format & args]
     (assert (string? format) (str "Message format in call to trf(" format args ") MUST be literal string (arguments can be variables)."))
     `(js/trf ~format ~@args)))
