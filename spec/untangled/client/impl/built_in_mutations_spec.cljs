(ns untangled.client.impl.built-in-mutations-spec
  (:require [om.next :as om]
            [untangled.client.mutations :as m]
            [untangled.client.impl.built-in-mutations]
            [untangled.client.impl.om-plumbing :as plumb]
            [untangled.i18n :as i18n]
            [untangled.client.logging :as log]
            [untangled.client.impl.data-fetch :as df])
  (:require-macros
    [cljs.test :refer [is]]
    [untangled-spec.core :refer [specification assertions behavior provided component when-mocking]]))


(specification "Mutation Helpers"
  (let [state (atom {:foo "bar"
                     :baz {:a {:b "c"}
                           :1 {:2 3
                               :4 false}}})]

    (component "set-value!"
      (behavior "can set a raw value"
        (when-mocking
          (om/transact! _ tx) => (let [tx-key (ffirst tx)
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
        (om/transact! _ tx) => (let [tx-key (ffirst tx)
                                     params (second (first tx))]
                                 ((:action (m/mutate {:state state :ref [:baz :1]} tx-key params))))

        (behavior "can toggle a boolean value"
          (m/toggle! '[:baz :1] :4)
          (is (get-in @state [:baz :1 :4]))
          (m/toggle! '[:baz :1] :4)
          (is (not (get-in @state [:baz :1 :4]))))))))

(specification "Mutations via transact"
  (let [state {}
        parser (partial (om/parser {:read plumb/read-local :mutate m/mutate}))
        reconciler (om/reconciler {:state  state
                                   :parser parser})]

    (behavior "can change the current localization."
      (reset! i18n/*current-locale* "en-US")
      (om/transact! reconciler `[(ui/change-locale {:lang "es-MX"}) :ui/locale])
      (is (= "es-MX" @i18n/*current-locale*)))

    (behavior "reports an error if an undefined multi-method is called."
      (when-mocking
        (log/error msg) => (is (re-find #"Unknown app state mutation." msg))
        (om/transact! reconciler `[(not-a-real-transaction!)])))))

(specification "Fallback mutations"
  (try
    (let [called (atom false)
          parser (om/parser {:read (fn [e k p] nil) :mutate m/mutate})]
      (defmethod m/mutate 'my-undo [e k p]
        (do
          (assertions
            "do not pass :action or :execute key to mutation parameters"
            (contains? p :action) => false
            (contains? p :execute) => false)
          {:action #(reset! called true)}))

      (behavior "are included in remote query if execute parameter is missing/false"
        (is (= '[(tx/fallback {:action my-undo})] (parser {} '[(tx/fallback {:action my-undo})] :remote)))
        (is (not @called)))
      (behavior "delegate to their action if the execute parameter is true"
        (parser {} '[(tx/fallback {:action my-undo :execute true})])
        (is @called)))

    (finally
      (-remove-method m/mutate 'my-undo))))

(specification "Load triggering mutation"
  (provided "triggers a mark-ready on the application state"
    (df/mark-ready args) => :marked

    (let [result (m/mutate {} 'untangled/load {})]
      ((:action result))

      (assertions
        "is remote"
        (:remote result) => true))))
