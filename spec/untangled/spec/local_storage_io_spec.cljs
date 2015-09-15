(ns untangled.spec.local-storage-io-spec
  (:require-macros [cljs.test :refer (is deftest run-tests testing)]
                   [smooth-spec.core :refer (specification behavior provided assertions with-timeline async)])
  (:require [cljs.test :refer [do-report]]
            [untangled.services.asyncio :as aio]
            [untangled.services.local-storage :as ls]
            [untangled.services.async-report :as ar]
            )
  )

(specification
  "Local Storage Io"
  (behavior
    "with no simulated delay or simulated timeout"
    (behavior "save of a new item adds the item to local storage and returns the saved item with a generated id"
              (let [async-report (ar/new-async-report #() #() #())
                    localio (ls/new-local-storage async-report 0 0)
                    state (atom [])
                    goodfn (fn [data] (reset! state data))
                    badfn (fn [error] (reset! state error))
                    ]
                (aio/save localio "testuri" goodfn badfn {:a 1})
                (assertions
                  (contains? @state :a) => true
                  (:a @state) => 1
                  (string? (:id @state)) => true
                  )
                )
              )
    (behavior "save updates an existing item and returns the saved item"
              (let [async-report (ar/new-async-report #() #() #())
                    localio (ls/new-local-storage async-report 0 0)
                    state (atom [])
                    goodfn (fn [data] (reset! state data))
                    badfn (fn [error] (reset! state error))
                    ]
                (aio/save localio "testuri" #() #() {:id "item1" :a 1})
                (aio/save localio "testuri" goodfn badfn {:id "item1" :b 2})
                (assertions
                  (contains? @state :b) => true
                  (:b @state) => 2
                  (string? (:id @state)) => true
                  (:id @state) => "item1"
                  )
                )
              )
    (behavior "fetch returns a single saved item"
              (let [async-report (ar/new-async-report #() #() #())
                    localio (ls/new-local-storage async-report 0 0)
                    state (atom [])
                    goodfn (fn [data] (reset! state data))
                    badfn (fn [error] (reset! state error))
                    ]
                (aio/save localio "testuri" #() #() {:id "item2" :a 1})
                (aio/fetch localio "testuri" goodfn badfn "item2")
                (assertions
                  (:a @state) => 1
                  (:id @state) => "item2"
                  )
                )
              )
    (behavior "query returns a list of saved items"
              (let [async-report (ar/new-async-report #() #() #())
                    localio (ls/new-local-storage async-report 0 0)
                    state (atom [])
                    goodfn (fn [data] (reset! state data))
                    badfn (fn [error] (reset! state error))
                    ]
                (aio/save localio "testuri" #() #() {:id "item1" :a 1})
                (aio/save localio "testuri" #() #() {:id "item2" :a 2})
                (aio/save localio "testuri" #() #() {:id "item3" :a 3})
                (aio/save localio "testuri" #() #() {:id "item4" :a 4})
                (aio/query localio "testuri" goodfn badfn)
                (assertions
                  (count @state) => 4
                  (count (filter #(= "item2" (:id %)) @state)) => 1
                  (:a (first (filter #(= "item2" (:id %)) @state))) => 2
                  )
                )
              )
    (behavior "delete deletes a single saved item"
              (let [async-report (ar/new-async-report #() #() #())
                    localio (ls/new-local-storage async-report 0 0)
                    state (atom [])
                    goodfn (fn [data] (reset! state data))
                    badfn (fn [error] (reset! state error))
                    ]
                (aio/save localio "testuri" #() #() {:id "item1" :a 1})
                (aio/save localio "testuri" #() #() {:id "item2" :a 2})
                (aio/save localio "testuri" #() #() {:id "item3" :a 3})
                (aio/save localio "testuri" #() #() {:id "item4" :a 4})
                (aio/delete localio "testuri" goodfn badfn "item3")
                (assertions
                  @state = "item3"
                  )
                (aio/query localio "testuri" goodfn badfn)
                (assertions
                  (count @state) => 3
                  )
                )
              )
    (behavior "fetch returns an error if the item is not found"
              (let [async-report (ar/new-async-report #() #() #())
                    localio (ls/new-local-storage async-report 0 0)
                    state (atom [])
                    goodfn (fn [data] (reset! state data))
                    badfn (fn [error] (reset! state error))
                    ]
                (aio/save localio "testuri" #() #() {:id "item1" :a 1})
                (aio/save localio "testuri" #() #() {:id "item2" :a 2})
                (aio/fetch localio "testuri" goodfn badfn "item5")
                (assertions
                  (:error @state) => :not-found
                  (:id @state) => "item5"
                  )
                )
              )
    (behavior "delete returns an error if the item is not found"
              (let [async-report (ar/new-async-report #() #() #())
                    localio (ls/new-local-storage async-report 0 0)
                    state (atom [])
                    goodfn (fn [data] (reset! state data))
                    badfn (fn [error] (reset! state error))
                    ]
                (aio/save localio "testuri" #() #() {:id "item1" :a 1})
                (aio/save localio "testuri" #() #() {:id "item2" :a 2})
                (aio/delete localio "testuri" goodfn badfn "item5")
                (assertions
                  (:error @state) => :not-found
                  (:id @state) => "item5"
                  )
                )
              )
    (behavior "query returns an empty list if no data is found"
              (let [async-report (ar/new-async-report #() #() #())
                    localio (ls/new-local-storage async-report 0 0)
                    state (atom [])
                    goodfn (fn [data] (reset! state data))
                    badfn (fn [error] (reset! state error))
                    ]
                (aio/query localio "testuri" goodfn badfn)
                (assertions
                  @state => []
                  )
                )
              ))
  (behavior
    "with simulated delay"
    (behavior "save sets the value after the timeout period has passed"
              ;(with-timeline
              ;
              ;
              ;
              ;  )
              )

    )
  ;(behavior "query has an error it times out"
  ;          (is (= 1 2))
  ;          )
  ;(behavior "fetch has an error it times out"
  ;          (is (= 1 2))
  ;          )
  ;(behavior "delete has an error it times out"
  ;          (is (= 1 2))
  ;          )
  ;(behavior "save has an error it times out"
  ;          (is (= 1 2))
  ;          )
  )

