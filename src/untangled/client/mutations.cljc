(ns untangled.client.mutations
  (:require
    #?(:clj [clojure.spec :as s])
            [om.next :as om]))

;; Add methods to this to implement your local mutations
(defmulti mutate om/dispatch)

;; Add methods to this to implement post mutation behavior (called after each mutation): WARNING: EXPERIMENTAL.
(defmulti post-mutate om/dispatch)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Mutation Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn toggle!
  "Toggle the given boolean `field` on the specified component. It is recommended you use this function only on
  UI-related data (e.g. form checkbox checked status) and write clear top-level transactions for anything more complicated."
  [comp field]
  (om/transact! comp `[(ui/toggle {:field ~field})]))

(defn set-value!
  "Set a raw value on the given `field` of a `component`. It is recommended you use this function only on
  UI-related data (e.g. form inputs that are used by the UI, and not persisted data)."
  [component field value]
  (om/transact! component `[(ui/set-props ~{field value})]))

#?(:cljs
   (defn- ensure-integer
     "Helper for set-integer!, use that instead. It is recommended you use this function only on UI-related
     data (e.g. data that is used for display purposes) and write clear top-level transactions for anything else."
     [v]
     (let [rv (js/parseInt v)]
       (if (js/isNaN v) 0 rv)))
   :clj
   (defn- ensure-integer [v] (Integer/parseInt v)))

(defn target-value [evt] (.. evt -target -value))

(defn set-integer!
  "Set the given integer on the given `field` of a `component`. Allows same parameters as `set-string!`.

   It is recommended you use this function only on UI-related data (e.g. data that is used for display purposes)
   and write clear top-level transactions for anything else."
  [component field & {:keys [event value]}]
  (assert (and (or event value) (not (and event value))) "Supply either :event or :value")
  (let [value (ensure-integer (if event (target-value event) value))]
    (set-value! component field value)))

(defn set-string!
  "Set a string on the given `field` of a `component`. The string can be literal via named parameter `:value` or
  can be auto-extracted from a UI event using the named parameter `:event`

  Examples

  ```
  (set-string! this :ui/name :value \"Hello\") ; set from literal (or var)
  (set-string! this :ui/name :event evt) ; extract from UI event target value
  ```

  It is recommended you use this function only on UI-related
  data (e.g. data that is used for display purposes) and write clear top-level transactions for anything else."
  [component field & {:keys [event value]}]
  (assert (and (or event value) (not (and event value))) "Supply either :event or :value")
  (let [value (if event (target-value event) value)]
    (set-value! component field value)))


#?(:clj (s/def ::action (s/cat
                          :action-name (fn [sym] (= sym 'action))
                          :action-args (fn [a] (and (vector? a) (= 1 (count a))))
                          :action-body (s/+ (constantly true)))))

#?(:clj (s/def ::remote (s/cat
                          :remote-name (fn [sym] (= sym 'remote))
                          :remote-args (fn [a] (and (vector? a) (= 1 (count a))))
                          :remote-body (s/+ (constantly true)))))

#?(:clj (s/def ::mutation-args (s/cat
                                 :sym symbol?
                                 :doc (s/? string?)
                                 :arglist vector?
                                 :action (s/? #(and (list? %) (= 'action (first %))))
                                 :remote (s/? #(and (list? %) (= 'remote (first %)))))))

#?(:clj (defn- conform! [spec x]
          (let [rt (s/conform spec x)]
            (when (s/invalid? rt)
              (throw (ex-info (s/explain-str spec x)
                       (s/explain-data spec x))))
            rt)))

#?(:clj
   (defmacro ^{:doc      "Define an Untangled mutation.

                       The given symbol will be prefixed with the namespace of the current namespace, as if
                       it were def'd into the namespace.

                       The arglist should be the *parameter* arglist of the mutation, NOT the complete argument list
                       for the equivalent defmethod. For example:

                          (defmutation boo [{:keys [id]} ...) => (defmethod m/mutate *ns*/boo [{:keys [state ref]} _ {:keys [id]}] ...)

                       The mutation may include an action and remote. Both are optional:

                       (defmutation boo \"docstring\" [params-map]
                         (action [env] ...)
                         (remote [env] ...))"
               :arglists '([sym docstring? arglist action]
                            [sym docstring? arglist action remote]
                            [sym docstring? arglist remote])} defmutation
     [& args]
     (let [{:keys [sym doc arglist action remote]} (conform! ::mutation-args args)
           fqsym      (symbol (name (ns-name *ns*)) (name sym))
           {:keys [action-args action-body]} (if action
                                               (conform! ::action action)
                                               {:action-args ['env] :action-body []})
           {:keys [remote-args remote-body]} (if remote
                                               (conform! ::remote remote)
                                               {:remote-args ['env] :remote-body [false]})
           remote-val `(do ~@remote-body)]
       `(defmethod untangled.client.mutations/mutate '~fqsym [env# ~'_ ~(first arglist)]
          (merge
            (let [~(first action-args) env#]
              {:action (fn [] ~@action-body)})
            (let [~(first remote-args) env#]
              {:remote ~remote-val}))))))

