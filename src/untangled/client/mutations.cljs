(ns untangled.client.mutations
  (:require [om.next :as om]
            [untangled.client.impl.data-fetch :as df]
            [untangled.i18n.core :as i18n]
            [untangled.client.logging :as log]))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutations (invoke using om/transact!)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti mutate om/dispatch)
(defmulti post-mutate om/dispatch)

(defmethod post-mutate :default [env k p] nil)

(defmethod mutate 'app/load [{:keys [state]} _ {:keys [root field query params without callback ident]}]
  {:remote true
   :action (fn []
             (df/mark-ready
               :state state
               :root root
               :field field
               :ident ident
               :query query
               :params params
               :without without
               :callback callback))})

(defmethod mutate 'app/clear-error [{:keys [state]} _ _]
  {:action #(swap! state assoc :last-error nil)})

(defmethod mutate 'app/change-locale [_ _ {:keys [lang]}]
  (reset! i18n/*current-locale* lang))

(defmethod mutate 'tx/fallback [env _ {:keys [action execute] :as params}]
  (if execute
    {:action #(mutate env action params)}
    {:remote true}))

(defmethod mutate 'ui/set-props [{:keys [state ref]} _ params]
  (when (nil? ref) (log/error "ui/set-props requires component to have an ident."))
  {:action #(swap! state update-in ref (fn [st] (merge st params)))})

(defmethod mutate 'ui/toggle [{:keys [state ref]} _ {:keys [field]}]
  (when (nil? ref) (log/error "ui/toggle requires component to have an ident."))
  {:action #(swap! state update-in (conj ref field) not)})

(defmethod mutate :default [{:keys [target]} k _]
  (when (nil? target)
    (log/error (log/value-message "Unknown app state mutation. Have you required the file with your mutations?" k))))
