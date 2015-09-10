(ns untangled.state-spec
  (:require-macros [cljs.test :refer (is deftest testing)]
                   [smooth-test.core :refer (specification behavior provided assertions)]
                   )
  (:require [untangled.history :as h]
            [untangled.state :as state]
            smooth-test.stub
            [cljs.test :refer [do-report]]
            [untangled.core :as core])
  )

(specification "Root scope"
               (let [the-application (core/new-application nil {})
                     root-scope (state/root-scope the-application)
                     ]
                 (behavior "is represented by a map"
                           (is (map? root-scope)))
                 (behavior "starts out with no event listeners"
                           (assertions
                             (contains? root-scope :event-listeners) => true
                             (empty? (root-scope :event-listeners)) => true
                             ))
                 (behavior "tracks the application"
                           (assertions
                             (:application root-scope) => the-application
                             ))
                 (behavior "starts the scope at the top"
                           (assertions
                             (:scope root-scope) => []
                             )
                           )))

(specification "data path"
               (let [state {:a {:b [{:k 2} {:k 1}]}}
                     the-application (core/new-application nil state) ; NOTE: adds a :top key to the state
                     scalar-path-context (assoc (state/root-scope the-application) :scope [:top :a :b 0])
                     context-with-sublist-path (assoc (state/root-scope the-application) :scope [:top :a [:b :k 1]])
                     context-with-bad-path (assoc (state/root-scope the-application) :scope [:top :a [:b :k]])
                     logging-happened (atom false)
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
                           (provided "logging goes to the console" 
                                     (println msg) => (do (reset! logging-happened true) (is (= "abc" msg)))
                                     (state/data-path context-with-bad-path) => [:top :a :b 1]
                                     )
                           )
                 ))
