(ns untangled.form
  (:require [untangled.events :as evt]))

;; Functions for working with FORM elements.

(defn input-on-change-handler
  "Generates a function that can set a component field to the DOM inputs value onChange.
  
  Parameters
  - `op` The component op-builder for the component's context
  - `f` A function that takes a string and your component state, and updates your component state with the new input's value.
  - `args` Named parameters to pass on to op, such as `:trigger :evt`
  
  Returns a function that can be used as an input onChange handler
  "
  [op f & args]
  (fn [evt] ((apply op (partial f (evt/text-value evt)) args))))
