(ns fulcro.client.mutations-spec
  (:require
    [fulcro-spec.core :refer [specification provided behavior assertions component when-mocking]]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [goog.debug.Logger.Level :as level]
    [fulcro.i18n :as i18n]
    [fulcro.client.impl.data-fetch :as df]
    [fulcro.client.core ]
    [fulcro.client.dom :as dom]
    [goog.log :as glog]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [clojure.test :refer [is]]
    [fulcro.client.logging :as log :refer [*logger*]]
    [clojure.string :as str]
    [fulcro.client.impl.application :as app]))

(defmutation sample
  "Doc string"
  [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state assoc :sample id))
  (remote [{:keys [ast]}]
    (assoc ast :params {:x 1})))

(specification "defmutation"
  (component "action"
    (let [state (atom {})
          ast   {}
          env   {:ast ast :state state}
          {:keys [action remote]} (m/mutate env `sample {:id 42})]

      (action)

      (assertions
        "Emits an action that has proper access to env and params"
        (:sample @state) => 42
        "Emits a remote that has the proper value"
        remote => {:params {:x 1}}))))

(specification "Mutation Helpers"
  (let [state (atom {:foo "bar"
                     :baz {:a {:b "c"}
                           :1 {:2 3
                               :4 false}}})]

    (component "set-value!"
      (behavior "can set a raw value"
        (when-mocking
          (prim/transact! _ tx) => (let [tx-key (ffirst tx)
                                         params (second (first tx))]
                                     ((:action (m/mutate {:state state :ref [:baz :1]} tx-key params))))

          (let [get-data #(-> @state :baz :1 :2)]
            (is (= 3 (get-data)))
            (m/set-value! '[:baz :1] :2 4)
            (is (= 4 (get-data)))))))

    (component "set-string!"
      (when-mocking
        (m/set-value! _ field value) => (assertions
                                          field => :b
                                          value => "d")
        (behavior "can set a raw string"
          (m/set-string! '[:baz :a] :b :value "d"))
        (behavior "can set a string derived from an event"
          (m/set-string! '[:baz :a] :b :event #js {:target #js {:value "d"}}))))

    (component "set-integer!"
      (when-mocking
        (m/set-value! _ field value) => (assertions
                                          field => :2
                                          value => 6)

        (behavior "can set a raw integer"
          (m/set-integer! '[:baz 1] :2 :value 6))
        (behavior "coerces strings to integers"
          (m/set-integer! '[:baz 1] :2 :value "6"))
        (behavior "can set an integer derived from an event target value"
          (m/set-integer! '[:baz 1] :2 :event #js {:target #js {:value "6"}})))

      (when-mocking
        (m/set-value! _ field value) => (assertions
                                          field => :2
                                          value => 0)

        (behavior "coerces invalid strings to 0"
          (m/set-integer! '[:baz 1] :2 :value "as"))))

    (component "toggle!"
      (when-mocking
        (prim/transact! _ tx) => (let [tx-key (ffirst tx)
                                       params (second (first tx))]
                                   ((:action (m/mutate {:state state :ref [:baz :1]} tx-key params))))

        (behavior "can toggle a boolean value"
          (m/toggle! '[:baz :1] :4)
          (is (get-in @state [:baz :1 :4]))
          (m/toggle! '[:baz :1] :4)
          (is (not (get-in @state [:baz :1 :4]))))))))

(specification "Mutations via transact"
  (let [state      {}
        parser     (partial (prim/parser {:read (partial app/read-local (constantly false)) :mutate m/mutate}))
        reconciler (prim/reconciler {:state  state
                                     :parser parser})]
    (behavior "report an error if an undefined multi-method is called."
      (when-mocking
        (log/error msg) => (is (re-find #"Unknown app state mutation." msg))
        (prim/transact! reconciler `[(not-a-real-transaction!)])))))

(specification "Change locale mutation"
  (behavior "accepts a string for locale"
    (when-mocking
      (m/locale-present? l) => true

      (reset! i18n/*current-locale* "en-US")

      (m/change-locale-impl {} "es-MX")

      (assertions @i18n/*current-locale* => "es-MX")))
  (behavior "accepts a keyword for locale"
    (when-mocking
      (m/locale-present? l) => true

      (reset! i18n/*current-locale* "en-US")
      (m/change-locale-impl {} :es-MX)
      (assertions @i18n/*current-locale* => "es-MX")))
  (provided "the locale is loaded"
    (m/locale-present? l) => (do
                               (assertions
                                 "The locale check is passed a stringified version of the lang"
                                 l => "es-MX"))
    (reset! i18n/*current-locale* "en-US")

    (let [new-state (m/change-locale-impl {} :es-MX)]
      (assertions
        "The locale is updated in app state as a string"
        (:ui/locale new-state) => "es-MX"
        "The react key is changed to force a DOM refresh"
        (:ui/react-key new-state) => "es-MX"
        "The global locale atom is updated"
        @i18n/*current-locale* => "es-MX")))
  (provided "the locale is not loaded, and is invalid"
    (m/locale-present? l) => false
    (m/locale-loadable? l) => (do
                                (assertions
                                  "the loadable check is passed a keyword version of the locale"
                                  l => :ja)
                                false)
    (log/error error) => (assertions
                           "Logs a console error"
                           error =fn=> #(str/starts-with? % "Attempt to change locale to ja"))

    (let [new-state (m/change-locale-impl {:x 1} "ja")]

      (assertions
        "Returns the original (unmodified) state"
        new-state => {:x 1})))
  (provided "the locale is not loaded, but is defined in a module"
    (m/locale-present? l) => false
    (m/locale-loadable? l) => true
    (cljs.loader/load m) => (assertions
                              "Triggers a module load with the locale's module keyword"
                              m => :ja)

    (let [new-state (m/change-locale-impl {} "ja")]
      (assertions
        "The locale is updated in app state as a string"
        (:ui/locale new-state) => "ja"
        "The react key is changed to force a DOM refresh"
        (:ui/react-key new-state) => "ja"
        "The global locale atom is updated"
        @i18n/*current-locale* => "ja"))))

(specification "Fallback mutations"
  (try
    (let [called (atom false)
          parser (prim/parser {:read (fn [e k p] nil) :mutate m/mutate})]
      (defmethod m/mutate 'my-undo [e k p]
        (do
          (assertions
            "do not pass :action or :execute key to mutation parameters"
            (contains? p :action) => false
            (contains? p :execute) => false)
          {:action #(reset! called true)}))

      (behavior "are remote-only if execute parameter is missing/false (tx/fallback)"
        (is (= '[(tx/fallback {:action my-undo})] (parser {} '[(tx/fallback {:action my-undo})] :remote)))
        (is (not @called)))

      (reset! called false)

      (behavior "are remote-only if execute parameter is missing/false (df/fallback)"
        (is (= '[(fulcro.client.data-fetch/fallback {:action my-undo})] (parser {} '[(fulcro.client.data-fetch/fallback {:action my-undo})] :remote)))
        (is (not @called)))

      (reset! called false)

      (behavior "delegate to their action if the execute parameter is true (tx/fallback)"
        (parser {} '[(tx/fallback {:action my-undo :execute true})])
        (is @called))

      (reset! called false)

      (behavior "delegate to their action if the execute parameter is true (df/fallback)"
        (parser {} '[(fulcro.client.data-fetch/fallback {:action my-undo :execute true})])
        (is @called)))

    (finally
      (-remove-method m/mutate 'my-undo))))

(specification "Load triggering mutation"
  (provided "triggers a mark-ready on the application state"
    (df/mark-ready args) => :marked

    (let [result (m/mutate {} 'fulcro/load {})]
      ((:action result))

      (assertions
        "is remote"
        (:remote result) => true))))

(defsc Item [this props _ _]
  {:query [:db/id :x]
   :ident [:table/id :db/id]}
  (dom/div nil ""))

(specification "Remote returning (declaring return value for a remote operation)"
  (let [ast (-> (prim/query->ast '[(f {:x 1})]) :children first)]
    (assertions
      "Returns an AST with the corresponding query for the type"
      (m/returning ast {} Item) => {:dispatch-key 'f
                                    :key          'f
                                    :params       {:x 1}
                                    :type         :call
                                    :query        [:db/id :x]
                                    :component    fulcro.client.mutations-spec/Item
                                    :children     [{:type         :prop
                                                    :dispatch-key :db/id
                                                    :key          :db/id}
                                                   {:type         :prop
                                                    :dispatch-key :x
                                                    :key          :x}]})))

(specification "Remote with-params (modify remote params)"
  (let [ast (-> (prim/query->ast '[(f {:x 1})]) :children first)]
    (assertions
      "Returns an AST with the parameters updated"
      (m/with-params ast {:y 2}) => {:dispatch-key 'f
                                     :key          'f
                                     :params       {:y 2}
                                     :type         :call})))
