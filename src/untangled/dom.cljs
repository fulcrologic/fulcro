(ns untangled.dom
  (:require [clojure.string :as str]
            [cljs-uuid-utils.core :as uuid]))



(defn unique-key [] (uuid/uuid-string (uuid/make-random-squuid)))


(defn toggle-class
  "Adds the 'visble' class and removes the 'hidden' class to the pre-supplied class string based on the truthiness
  of the value in data at key.

  Parameters:
  `data`: A map containg the component's state.
  `key`: A key within `data`.
  `always-classes`: A string that has the CSS classes to always return in the returned string.

  Optional named parameters:

  `:when-true v` : This string to add when the key's value is true. Defaults to \"active\".
  `:when-false v` : The string to add when the key's value is false. Defaults to \"\".
  "
  [data key always-classes & {:keys [when-true when-false]
                              :or   {when-true "active" when-false ""}}]
  (if (get data key)
    (str/join " " [always-classes when-true])
    (str/join " " [always-classes when-false])))


(defn toggle
  "Toggles the (boolean) state of an arbitrary state key.

  Params:
  `state-key` : A key within the component state.
  `state`     : The map containing the current state of a component.
  "
  [state-key state] (update state state-key not))


(defn toggle-checked
  "Toggles the (boolean) state of the `checked` attribute of an input element."
  [input]
  (update input :checked not))


(defn text-value
  "Returns the text value from an input change event"
  [evt]
  (.-value (.-target evt)))
