(ns untangled.client.mutations
  (:require [om.next :as om]))

;; Add methods to this to implement your local mutations
(defmulti mutate om/dispatch)

;; Add methods to this to implement post mutation behavior (called after each mutation): WARNING: EXPERIMENTAL.
(defmulti post-mutate om/dispatch)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Mutation Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn toggle!
  "Toggle the given boolean `field` on the specified component."
  [comp field]
  (om/transact! comp `[(ui/toggle {:field ~field})]))

(defn set-value!
  "Set a raw value on the given `field` of a `component`."
  [component field value]
  (om/transact! component `[(ui/set-props ~{field value})]))

(defn- ensure-integer
  "Helper for set-integer!, use that instead."
  [v]
  (let [rv (js/parseInt v)]
    (if (js/isNaN v) 0 rv)))

(defn set-integer!
  "Set the given integer on the given `field` of a `component`. Allows same parameters as `set-string!`"
  [component field & {:keys [event value]}]
  (assert (and (or event value) (not (and event value))) "Supply either :event or :value")
  (let [value (ensure-integer (if event (.. event -target -value) value))]
    (set-value! component field value)))

(defn set-string!
  "Set a string on the given `field` of a `component`. The string can be literal via named parameter `:value` or
  can be auto-extracted from a UI event using the named parameter `:event`

  Examples

  ```
  (set-string! this :ui/name :value \"Hello\") ; set from literal (or var)
  (set-string! this :ui/name :event evt) ; extract from UI event target value
  ```
  "
  [component field & {:keys [event value]}]
  (assert (and (or event value) (not (and event value))) "Supply either :event or :value")
  (let [value (if event (.. event -target -value) value)]
    (set-value! component field value)))

