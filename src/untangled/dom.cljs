(ns untangled.dom
  (:require [clojure.string :as str]))

(defn hide-show-class
  "Adds the 'visble' class and removes the 'hidden' class to the pre-supplied class string based on the truthiness
  of the value in data at key.

  Parameters:
  `data`: Data
  `key`: Key within data
  `always-classes`: A string that has the CSS classes to always return in the returned string.

  Optional named parameters:

  `:hidden v` : The string to add when the key's value is false. Defaults to \"hidden\"
  `:visible v` : This string to add when the key's value is true. Defaults to \"\".
  "
  [data key always-classes & {:keys [hidden visible] :or {hidden "hidden" visible ""}}]
  (if (get data key)
    (str/join " " [always-classes visible])
    (str/join " " [always-classes hidden])
    )
  )
