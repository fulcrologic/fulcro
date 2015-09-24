(ns untangled.state-spec
  (:require-macros [cljs.test :refer (is deftest testing)]
                   [smooth-spec.core :refer (specification behavior provided assertions)]
                   )
  (:require [untangled.history :as h]
            [untangled.state :as state]
            [untangled.logging :as logging]
            [cljs.test :refer [do-report]]
            smooth-spec.stub
            cljs.pprint
            [untangled.core :as core]
            [untangled.application :as app]
            [untangled.events :as evt])
  )

(specification "Root scope -- root-scope function"
               (let [the-application (core/new-application nil {})
                     root-scope (state/root-context the-application)
                     ]
                 (behavior "is represented by a map"
                           (is (map? root-scope)))
                 (behavior "starts out with no event listeners"
                           (assertions
                             (contains? root-scope :untangled.state/event-listeners) => true
                             (empty? (root-scope :untangled.state/event-listeners)) => true
                             ))
                 (behavior "tracks the application"
                           (assertions
                             (:untangled.state/application root-scope) => the-application
                             ))
                 (behavior "starts the scope at the top"
                           (assertions
                             (:untangled.state/scope root-scope) => []
                             )
                           )))

(specification "data path conversion -- data-path function"
               (let [state {:a {:b [{:k 2} {:k 1} {:k 4}]}}
                     the-application (core/new-application nil state) ; NOTE: adds a :top key to the state
                     scalar-path-context (assoc (state/root-context the-application) :untangled.state/scope [:top :a :b 0])
                     context-with-sublist-path (assoc (state/root-context the-application) :untangled.state/scope [:top :a [:b :k 1 0]])
                     context-with-bad-path (assoc (state/root-context the-application) :untangled.state/scope [:top :a [:b :k]])
                     context-with-missing-data (assoc (state/root-context the-application) :untangled.state/scope [:top :a [:b :k 3 0]])
                     context-with-stale-index (assoc (state/root-context the-application) :untangled.state/scope [:top :a [:b :k 1 2]])
                     ]
                 (behavior "leaves paths containing only scalar values alone"
                           (assertions
                             (state/data-path scalar-path-context) => [:top :a :b 0]
                             ))
                 (behavior "converts inline vectors by looking up the given k/v pair in the targeted list"
                           (assertions
                             (state/data-path context-with-sublist-path) => [:top :a :b 1]
                             ))
                 (behavior "logs a console message (for debugging) if a inline vector is not a triple"
                           (provided "message indicates vector must have three elements"
                                     (logging/log msg) =1x=> (is (= "ERROR: VECTOR BASED DATA ACCESS MUST HAVE A 4-TUPLE KEY" msg))

                                     (state/data-path context-with-bad-path)
                                     ))
                 (behavior "logs a console message (for debugging) if a inline vector refers to a missing value"
                           (provided "message indicates that no item was found, and includes the path that was searched"
                                     (logging/log msg) =1x=> (is (= "ERROR: NO ITEM FOUND AT DATA PATH" msg))
                                     (cljs.pprint/pprint path) =1x=> (is (= [:top :a [:b :k 3 0]] path))

                                     (state/data-path context-with-missing-data)
                                     ))
                 (behavior "finds the correct object in a list that has an incorrect index suggestion"
                           (assertions
                             (state/data-path context-with-stale-index) => [:top :a :b 1]
                           ))
                 ))

(specification "Rendering context"
               (let [state {:a {:form/locale "en-US"
                                :b           [{:k 2 :v {:boo 22}} {:k 1}]}}
                     the-application (core/new-application nil state) ; NOTE: adds a :top key to the state
                     context-with-sublist-path (assoc (state/root-context the-application) :untangled.state/scope [:top :a [:b :k 1 1]])
                     ]
                 (behavior "context-data function retrieves the data for the provided context"
                           (provided "provided that data-path is a legal path"
                                     (state/data-path ctx) =1x=> [:top :a :b 1]

                                     (is (= (state/context-data context-with-sublist-path) {:k 1})))
                           )
                 (let [root (state/root-context the-application)
                       top (state/new-sub-context root :top [])
                       context1 (state/new-sub-context top :a [] #{:form/locale})
                       context2 (state/new-sub-context context1 [:b :k 2 0] [])
                       context3 (state/new-sub-context context2 :v [])
                       ]
                   (behavior "carries published data in the context"
                             (assertions
                               (:untangled.state/to-publish context1) => { :form/locale "en-US"}
                               (:untangled.state/to-publish context2) => { :form/locale "en-US"}
                               (:untangled.state/to-publish context3) => { :form/locale "en-US"}
                               )
                             )
                   (behavior "context-data copies published parent data into extracted data"
                             (assertions
                               (-> (state/context-data context1) :form/locale) => "en-US"
                               (-> (state/context-data context2) :form/locale) => "en-US"
                               (-> (state/context-data context3) :form/locale) => "en-US"
                               )
                             )
                   )))

(specification "the update-in-context function"
               (let [state {:a {:b [{:k 2} {:k 1 :v 0}]}}
                     the-application (core/new-application nil state) ; NOTE: adds a :top key to the state
                     context-with-sublist-path (assoc (state/root-context the-application) :untangled.state/scope [:top :a [:b :k 1 1]])
                     operation (fn [obj] (update obj :v inc))
                     tm (js/Date. 2000)
                     tm2 (js/Date. 3000)
                     ]

                 (behavior "evolves the application state using a function and a location indicated by context"
                           (provided "provided that state change event sent to application"
                                     ; TODO: assert arguments are valid
                                     (app/state-changed app old new) => nil
                                     (h/now) =1x=> tm
                                     (h/now) =1x=> tm2

                                     (state/update-in-context context-with-sublist-path operation true false "because")
                                     (state/update-in-context context-with-sublist-path operation false true "I said so")

                                     (behavior "applies the function to the localized state"
                                               (is (= 2 (get-in @(:app-state the-application) [:top :a :b 1 :v]))))
                                     (behavior "updates the time stamp in the application state"
                                               (is (= tm2 (:time @(:app-state the-application)))))
                                     (behavior "stores old state in history"
                                               (let [history @(:history the-application)
                                                     entries (:entries history)
                                                     latest-entry (first entries)
                                                     oldest-entry (last entries)
                                                     ]

                                                 (assertions
                                                   (count entries) => 2
                                                   (get-in (:app-state latest-entry) [:top :a :b 1 :v]) => 1
                                                   (get-in (:app-state oldest-entry) [:top :a :b 1 :v]) => 0
                                                   (-> latest-entry :app-state :time) => tm
                                                   )

                                                 (behavior "tracks history metadata -- undoable, collapse, reason"
                                                           (assertions
                                                             (:undoable oldest-entry) => true
                                                             (:undoable latest-entry) => false
                                                             (:can-collapse? oldest-entry) => false
                                                             (:can-collapse? latest-entry) => true
                                                             (:reason oldest-entry) => "because"
                                                             (:reason latest-entry) => "I said so"
                                                             )
                                                           )))))))

(specification
  "the new-scope function"
  (let [context (state/root-context "mock application")]
    (behavior
      "creates a new context representing one of its children's contexts"
      (behavior
        "with the proper new scope"
        (assertions
          (:untangled.state/scope (state/new-sub-context context :id {})) => [:id]
          ))
      (behavior
        "sets the event handlers"
        (assertions
          (:untangled.state/event-listeners (state/new-sub-context context :id [{:datePicked 'func}])) => [{:datePicked 'func}]
          ))
      (behavior
        "accumulates the event map entries from the parent context in vector (add-last) order"
        (let [parent-context (state/new-sub-context context :id [{:datePicked 'f1}])]
          (assertions
            (:untangled.state/event-listeners (state/new-sub-context parent-context :id [{:datePicked 'func}])) => [{:datePicked 'f1} {:datePicked 'func}]
            ))
        )
      ))
  )

(specification "the contex-operator function"
               (behavior "generates a function"
                         (let [generated-op-with-defaults (state/context-operator 'context 'operation)
                               generated-op (state/context-operator 'context 'operation :undoable false :compress true)
                               generated-op-with-reason (state/context-operator 'context 'operation :reason "because default")
                               generated-op-with-trigger (state/context-operator 'context 'operation :trigger 'events)
                               ]
                           (behavior "which when called"
                                     (provided "does an update where undo defaults to true, compresable false."
                                               (state/update-in-context ctx op u c r) => (do
                                                                                           (is (= u true))
                                                                                           (is (= c false))
                                                                                           )
                                               (generated-op-with-defaults))
                                     (provided "has a default reason of nil."
                                               (state/update-in-context ctx op u c r) => (is (nil? r))

                                               (generated-op-with-defaults))
                                     (provided "can assign default reason and override the default reason."
                                               (state/update-in-context ctx op u c r) =1x=> (is (= r "because default"))
                                               (state/update-in-context ctx op u c r) => (is (= r "because override"))

                                               (generated-op-with-reason)
                                               (generated-op-with-reason :reason "because override"))
                                     (provided "does an update that overrides default values for undo, compressable."
                                               (state/update-in-context ctx op u c r) => (do
                                                                                           (is (= u false))
                                                                                           (is (= c true))
                                                                                           )
                                               (generated-op))
                                     (provided "will trigger default triggers for the context, which can be overridden."
                                               (state/update-in-context ctx op u c r) => 'anything
                                               (evt/trigger ctx trig) =1x=> (is (= trig 'events))
                                               (evt/trigger ctx trig) => (is (= trig 'others))

                                               (generated-op-with-trigger)
                                               (generated-op-with-trigger :trigger 'others)
                                               )))))

(specification "the op-builder function"
               (behavior "is equivalent to the partial application of context-operator"
                         (provided "given a context"
                                   (partial f ctx) => (do
                                                        (is (= f state/context-operator))
                                                        (is (= 'some-context ctx)))

                                   (state/op-builder 'some-context)
                                   )))


(specification "List element id"
               (behavior "emits a warning if you supply a key name that does not exist in the actual data item."
                         ))
