(ns untangled.ui.bootstrap3-forms
  (:require [untangled.ui.bootstrap3 :as b]
            ;[untangled.ui.forms :as f]
            [om.dom :as dom]
            [untangled.i18n :refer [tr]]
            [clojure.string :as str]))

(comment
  (defn render-form-element
    "Render a forms support control with bootstrap CSS.

    component - The UI component controlling the form
    form - The Untangled form
    element - The form element to render
    split - nil or the number of columns for the label
    validation-message - The message to show when this field is invalid"
    [component form element split label validation-message]
    (when (contains? (.getMethodTable f/form-field*) (:input/type element))
      (let [field-name         (:input/name element)
            field-id           (str/join "-" (conj (f/form-ident form) (:input/name element)))
            valid?             (f/valid? form element)
            invalid?           (f/invalid? form element)
            help               (or validation-message (tr "Invalid input"))
            state-class        (cond
                                 invalid? " has-error"
                                 valid? " has-success"
                                 :else "")
            form-group-classes (str "form-group" state-class)
            split-right        (- 12 split)
            help-id            (str field-id "-help")]
        (if (int? split)
          (dom/div #js {:className form-group-classes}
            (dom/label #js {:className (str "control-label col-sm-" split) :htmlFor field-id} label)
            (dom/div #js {:className (str "col-sm-" split-right)}
              (f/form-field component form field-name :id field-id :className "form-control")
              (when help (dom/span #js {:id help-id :className "help-block"} help))))
          (dom/div #js {:className form-group-classes}
            (dom/label #js {:className "control-label" :htmlFor field-id} label)
            (f/form-field component form field-name :id field-id :className "form-control")
            (when help (dom/span #js {:id help-id :className "help-block"} help)))))))

  ; TODO: Radio buttons are a special case with respect to IDs and labels

  (defn render-horizonal-form
    "Renders (non-recursively) the given form using bootstrap CSS (which must be on your page).

    label-map is a map from input keyword to a label. Since this is re-evaluated at every render, you may use `tr` to
    internationalize the labels.

    Options:
    The form will be rendered so that the label is to the left of the input using a responsive grid. The left
    column width(s) can be specified in options, and the right column widths will be derived from them to be the
    remainder of the grid width of 12.

    - xs The width of the label column on xs screens
    - sm The width of the label column on sm screens
    - md The width of the label column on md screens
    - lg The width of the label column on lg screens

    "
    [component form-class form label-map validation-map {:keys [xs sm md lg] :as options}]
    (let [elements (f/get-form-spec* form-class)]
      (dom/div #js {:className "form-horizontal"}
        (map (fn [element] (render-form-element component form element 4 (get label-map (:input/name element) "")
                             (get validation-map (:input/name element) (tr "Invalid input")))) elements)))))
