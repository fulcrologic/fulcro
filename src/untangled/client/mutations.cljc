(ns untangled.client.mutations
  #?(:cljs (:require-macros untangled.client.mutations))
  (:require
    [clojure.spec :as s]
    [om.next :as om]
    [untangled.client.util :refer [conform!]]
    [untangled.client.logging :as log]
    [untangled.i18n :as i18n]))

;; Add methods to this to implement your local mutations
(defmulti mutate om/dispatch)

;; Add methods to this to implement post mutation behavior (called after each mutation): WARNING: EXPERIMENTAL.
(defmulti post-mutate om/dispatch)
(defmethod post-mutate :default [env k p] nil)

#?(:cljs
   (untangled.client.mutations/defmutation change-locale
     "Om mutation: Change the locale of the UI."
     [{:keys [lang]}]
     (action [{:keys [state]}]
       (reset! i18n/*current-locale* lang)
       (swap! state #(-> %
                       (assoc :ui/locale lang)
                       (assoc :ui/react-key lang))))))


#?(:cljs
   (untangled.client.mutations/defmutation set-props
     "
     Om mutation: A convenience helper, generally used 'bit twiddle' the data on a particular database table (using the component's ident).
     Specifically, merge the given `params` into the state of the database object at the component's ident.
     In general, it is recommended this be used for ui-only properties that have no real use outside of the component.
     "
     [params]
     (action [{:keys [state ref]}]
       (when (nil? ref) (log/error "ui/set-props requires component to have an ident."))
       (swap! state update-in ref (fn [st] (merge st params))))))

#?(:cljs
   (untangled.client.mutations/defmutation toggle
     "Om mutation: A helper method that toggles the true/false nature of a component's state by ident.
      Use for local UI data only. Use your own mutations for things that have a good abstract meaning. "
     [{:keys [field]}]

     (action [{:keys [state ref]}]
       (when (nil? ref) (log/error "ui/toggle requires component to have an ident."))
       (swap! state update-in (conj ref field) not))))

(defmethod mutate :default [{:keys [target]} k _]
  (when (nil? target)
    (log/error (log/value-message "Unknown app state mutation. Have you required the file with your mutations?" k))))


(defn toggle!
  "Toggle the given boolean `field` on the specified component. It is recommended you use this function only on
  UI-related data (e.g. form checkbox checked status) and write clear top-level transactions for anything more complicated."
  [comp field]
  (om/transact! comp `[(toggle {:field ~field})]))

(defn set-value!
  "Set a raw value on the given `field` of a `component`. It is recommended you use this function only on
  UI-related data (e.g. form inputs that are used by the UI, and not persisted data)."
  [component field value]
  (om/transact! component `[(set-props ~{field value})]))

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
                          :remote-name symbol?
                          :remote-args (fn [a] (and (vector? a) (= 1 (count a))))
                          :remote-body (s/+ (constantly true)))))

#?(:clj (s/def ::mutation-args (s/cat
                                 :sym symbol?
                                 :doc (s/? string?)
                                 :arglist vector?
                                 :action (s/? #(and (list? %) (= 'action (first %))))
                                 :remote (s/* #(and (list? %) (not= 'action (first %)))))))

#?(:clj
   (defmacro ^{:doc      "Define an Untangled mutation.

                       The given symbol will be prefixed with the namespace of the current namespace, as if
                       it were def'd into the namespace.

                       The arglist should be the *parameter* arglist of the mutation, NOT the complete argument list
                       for the equivalent defmethod. For example:

                          (defmutation boo [{:keys [id]} ...) => (defmethod m/mutate *ns*/boo [{:keys [state ref]} _ {:keys [id]}] ...)

                       The mutation may include any combination of action and any number of remotes (by the remote name).

                       If `action` is supplied, it must be first.

                       (defmutation boo \"docstring\" [params-map]
                         (action [env] ...)
                         (my-remote [env] ...)
                         (other-remote [env] ...)
                         (remote [env] ...))"
               :arglists '([sym docstring? arglist action]
                            [sym docstring? arglist action remote]
                            [sym docstring? arglist remote])} defmutation
     [& args]
     (let [{:keys [sym doc arglist action remote]} (conform! ::mutation-args args)
           fqsym         (symbol (name (ns-name *ns*)) (name sym))
           {:keys [action-args action-body]} (if action
                                               (conform! ::action action)
                                               {:action-args ['env] :action-body []})
           remotes       (if (seq remote)
                           (map #(conform! ::remote %) remote)
                           [{:remote-name :remote :remote-args ['env] :remote-body [false]}])
           env-symbol    (gensym "env")
           remote-blocks (map (fn [{:keys [remote-name remote-args remote-body]}]
                                `(let [~(first remote-args) ~env-symbol]
                                   {~(keyword (name remote-name)) (do ~@remote-body)})
                                ) remotes)]
       `(defmethod untangled.client.mutations/mutate '~fqsym [~env-symbol ~'_ ~(first arglist)]
          (merge
            (let [~(first action-args) ~env-symbol]
              {:action (fn [] ~@action-body)})
            ~@remote-blocks)))))

