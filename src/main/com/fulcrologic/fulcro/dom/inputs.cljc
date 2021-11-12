(ns com.fulcrologic.fulcro.dom.inputs
  "A namespace for dealing with inputs in HTML DOM when you wish to control a value in the data model
  that cannot be directly represented by normal HTML inputs (which always use strings). For example, you want to have an int in
  your data model, but HTML5 number inputs return a string. The primary utility is `StringBufferedInput` which generates
  a new React class that wraps an HTML `input`. The namespace also includes a few uses that are handy (at least as
  examples): `ui-int-input` and `ui-keyword-input`. See the source of those for examples."
  (:require
    #?@(:cljs
        [["react" :as react]
         [goog.object :as gobj]])
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.dom.events :as evt]))

(defn StringBufferedInput
  "Create a new type of input that can be derived from a string. `kw` is a fully-qualified keyword name for the new
  class (which will be used to register it in the component registry), and `model->string` and `string->model` are
  functions that can do the conversions (and MUST tolerate nil as input).
  `model->string` MUST return a string (empty if invalid), and `string->model` should return nil if the string doesn't
  yet convert to a valid model value.

  `string-filter` is an optional `(fn [string?] string?)` that can be used to rewrite incoming strings (i.e. filter
  things).
  "
  [kw {:keys [model->string
              string->model
              string-filter]}]
  (let [cls (fn [props]
              #?(:cljs
                 (cljs.core/this-as this
                   (let [props         (gobj/get props "fulcro$value")
                         {:keys [value]} props
                         initial-state {:oldPropValue value
                                        :on-change    (fn [evt]
                                                        (let [{:keys [value onChange]} (comp/props this)
                                                              nsv (evt/target-value evt)
                                                              nv  (string->model nsv)]
                                                          (comp/set-state! this {:stringValue  nsv
                                                                                 :oldPropValue value
                                                                                 :value        nv})
                                                          (when (and onChange (not= value nv))
                                                            (onChange nv))))
                                        :stringValue  (model->string value)}]
                     (set! (.-state this) (cljs.core/js-obj "fulcro$state" initial-state)))
                   nil)))]
    (comp/configure-component! cls kw
      {:getDerivedStateFromProps
       (fn [latest-props state]
         (let [{:keys [value]} latest-props
               {:keys [oldPropValue stringValue]} state
               ignorePropValue?  (or (= oldPropValue value) (= value (:value state)))
               stringValue       (cond-> (if ignorePropValue?
                                           stringValue
                                           (model->string value))
                                   string-filter string-filter)
               new-derived-state (merge state {:stringValue stringValue :oldPropValue value})]
           #js {"fulcro$state" new-derived-state}))
       :render
       (fn [this]
         #?(:cljs
            (let [{:keys [value onBlur] :as props} (comp/props this)
                  {:keys [stringValue on-change]} (comp/get-state this)]
              (react/createElement "input"
                (clj->js
                  (merge props
                    (cond->
                      {:value    stringValue
                       :onChange on-change}
                      onBlur (assoc :onBlur (fn [evt]
                                              (onBlur (-> evt evt/target-value string->model)))))))))))})
    (comp/register-component! kw cls)
    cls))

(defn symbol-chars
  "Returns `s` with all non-digits stripped."
  [s]
  (str/replace s #"[\s\t:]" ""))

(def ui-keyword-input
  "A keyword input. Used just like a DOM input, but requires you supply nil or a keyword for `:value`, and
   will send a keyword to `onChange` and `onBlur`. Any other attributes in props are passed directly to the
   underlying `dom/input`."
  (comp/factory (StringBufferedInput ::KeywordInput {:model->string #(str (some-> % name))
                                                     :string-filter symbol-chars
                                                     :string->model #(when (seq %)
                                                                       (some-> % keyword))})))
(defn to-int
  "Convert a string `s`"
  [s]
  #?(:clj
     (try
       (Long/parseLong s)
       (catch Exception _
         nil))
     :cljs
     (let [n (js/parseInt s)]
       (when-not (js/isNaN n)
         n))))

(let [digits (into #{} (map str) (range 10))]
  (defn just-digits
    "Returns `s` with all non-digits stripped."
    [s]
    (str/join
      (filter digits (seq s)))))

(def ui-int-input
  "An integer input. Can be used like `dom/input` but onChange and onBlur handlers will be passed an int instead of
  a raw react event, and you should supply an int for `:value` instead of a string.  You may set the `:type` to text
  or number depending on how you want the control to display, even though the model value is always an int or nil.
  All other attributes passed in props are passed through to the contained `dom/input`."
  (comp/factory (StringBufferedInput ::IntInput {:model->string str
                                                 :string->model to-int
                                                 :string-filter just-digits})))

