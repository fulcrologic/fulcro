(ns untangled.dom
  (:require [clojure.string :as str]
            [cljs-uuid-utils.core :as uuid]
            [untangled.logging :as logging]))



(defn unique-key [] (uuid/uuid-string (uuid/make-random-squuid)))


(defn toggle-class
  "Adds the 'visible' class and removes the 'hidden' class to the pre-supplied class string based on the truthiness
  of the value in data at key.

  Parameters:
  `data`: A map containing the component's state.
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

(defn text-value
  "Returns the text value from an input change event."
  [evt]
  (try
    (.-value (.-target evt))
    (catch js/Object e (logging/warn "Event had no target when trying to pull text"))
    )
  )
