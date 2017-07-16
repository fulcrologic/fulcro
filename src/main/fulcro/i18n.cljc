(ns fulcro.i18n
  #?(:cljs (:require-macros fulcro.i18n))
  (:require
    [fulcro.client.logging :as log]
    #?(:cljs yahoo.intl-messageformat-with-locales))
  #?(:clj
     (:import (com.ibm.icu.text MessageFormat)
              (java.util Locale))))

(def ^:dynamic *current-locale* (atom "en-US"))
(def ^:dynamic *loaded-translations* (atom {}))
(defn current-locale [] @*current-locale*)
(defn translations-for-locale [] (get @*loaded-translations* (current-locale)))

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

(defmacro if-cljs
  [then else]
  (if (:ns &env) then else))

(letfn [(real-tr [msg]
          (let [msg-key      (str "|" msg)
                translations (translations-for-locale)
                translation  (get translations msg-key msg)]
            translation))]
  #?(:clj
     (defn tr-ssr [msg] (real-tr msg))
     :cljs
     (set! js/tr (fn tr [msg] (real-tr msg)))))

(letfn [(real-trc [ctxt msg]
          (let [msg-key      (str ctxt "|" msg)
                translations (translations-for-locale)
                translation  (get translations msg-key msg)]
            translation))]
  #?(:clj
     (defn trc-ssr [ctxt msg] (real-trc ctxt msg))
     :cljs
     (set! js/trc (fn [ctxt msg] (real-trc ctxt msg)))))

#?(:clj
   (defn trf-ssr
     [fmt & {:keys [] :as args}]
     (try
       (let [argmap       (into {} (map (fn [[k v]] [(name k) v]) args))
             msg-key      (str "|" fmt)
             translations (translations-for-locale)
             translation  (get translations msg-key fmt)
             formatter    (new MessageFormat translation (Locale/forLanguageTag (current-locale)))]
         (.format formatter argmap))
       (catch Exception e
         (log/error "Failed to format " fmt " args: " args " exception: " e)
         "???")))
   :cljs
   (set! js/trf
     (fn trf [fmt & {:keys [] :as argmap}]
       (try
         (let [msg-key      (str "|" fmt)
               translations (translations-for-locale)
               translation  (get translations msg-key fmt)
               formatter    (js/IntlMessageFormat. translation (current-locale))]
           (.format formatter (clj->js argmap)))
         (catch :default e (log/error "Failed to format " fmt " args: " argmap " exception: " e)
                           "???")))))

#?(:clj
   (defmacro tr-unsafe
     "Look up the given message. UNSAFE: you can use a variable with this, and thus string extraction will NOT
     happen for you. This means you have to use some other mechanism to make sure the string ends up in translation
     files (such as manually calling tr on the various raw string values elsewhere in your program)"
     [msg]
     `(if-cljs (js/tr ~msg) (tr-ssr ~msg))))

#?(:clj
   (defmacro trlambda
     "Translate the given literal string. The argument MUST be a literal string so that it can be properly extracted
     for use in gettext message files as the message key. This macro throws a detailed assertion error if you
     violate this restriction. See trf for generating translations that require formatting (e.g. construction from
     variables)."
     [msg]
     (let [{:keys [line]} (meta &form)
           msg (if (string? msg)
                 msg
                 (str "ERROR: tr-lambda requires a literal string on line " line " in " (str *ns*)))]
       `(fn [] (if-cljs (js/tr ~msg) (tr-ssr ~msg))))))

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
       `(if-cljs (js/tr ~msg) (tr-ssr ~msg)))))

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
       `(if-cljs (js/trc ~context ~msg) (trc-ssr ~context ~msg)))))

#?(:clj
   (defmacro trf
     "Translate a format string, then use it to format a message with the given arguments. The format MUST be a literal
     string for extraction by gettext. The arguments should be keyword/value pairs that will match the embedded
     items to format.

     (trf \"{name} owes {amount, currency)\" :name who :amount amt)

     The format string is an ICU message format. See FormatJS for details.
     "
     [format & args]
     (let [{:keys [line]} (meta &form)
           [format args] (if (string? format)
                           [format args]
                           ["ERROR: trf requires a literal string on line {line} in {file}" [:line line :file (str *ns*)]])]
       `(if-cljs (js/trf ~format ~@args) (trf-ssr ~format ~@args)))))
