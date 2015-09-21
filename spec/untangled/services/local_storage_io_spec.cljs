(ns untangled.services.local-storage-io-spec
  (:require-macros [cljs.test :refer (is deftest run-tests testing)]
                   [smooth-spec.core :refer (specification behavior provided assertions with-timeline async tick)])
  (:require [cljs.test :refer [do-report]]
            [smooth-spec.async]
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
                    localio (ls/new-local-storage async-report 0)
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
                    localio (ls/new-local-storage async-report 0)
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
                    localio (ls/new-local-storage async-report 0)
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
                    localio (ls/new-local-storage async-report 0)
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
                    localio (ls/new-local-storage async-report 0)
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
                    localio (ls/new-local-storage async-report 0)
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
                    localio (ls/new-local-storage async-report 0)
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
                    localio (ls/new-local-storage async-report 0)
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
    (behavior "save calls the good callback after the timeout period has passed"
              (with-timeline
                (let [async-report (ar/new-async-report #() #() #())
                      localio (ls/new-local-storage async-report 100)
                      state (atom [])
                      goodfn (fn [data] (reset! state data))
                      badfn (fn [error] (reset! state error))
                      ]
                  (provided "when mocking setTimeout"
                            (js/setTimeout f n) => (async n (f))
                            (aio/save localio "testuri" goodfn badfn {:a 1})
                            (behavior "nothing is called until after the simualed delay is passed"
                                      (is (= @state []))
                                      )
                            (behavior "item is saved when simualed delay is passed"
                                      (tick 100)
                                      (is (= (:a @state) 1))
                                      )
                            )
                  )
                )
              )
    (behavior "query calls the good callback after the timeout period has passed"
              (with-timeline
                (let [async-report (ar/new-async-report #() #() #())
                      localio (ls/new-local-storage async-report 100)
                      state (atom [])
                      goodfn (fn [data] (reset! state data))
                      badfn (fn [error] (reset! state error))
                      ]
                  (provided "when mocking setTimeout"
                            (js/setTimeout f n) =5=> (async n (f))
                            (aio/save localio "testuri" #() #() {:id "item1" :a 1})
                            (tick 100)
                            (aio/save localio "testuri" #() #() {:id "item2" :a 2})
                            (tick 100)
                            (aio/save localio "testuri" #() #() {:id "item3" :a 3})
                            (tick 100)
                            (aio/save localio "testuri" #() #() {:id "item4" :a 4})
                            (tick 100)
                            (aio/query localio "testuri" goodfn badfn)
                            (behavior "nothing is called until after the simualed delay is passed"
                                      (is (= @state []))
                                      )
                            (behavior "query has returend when simualed delay is passed"
                                      (tick 100)
                                      (is (= (count @state) 4))
                                      )
                            )
                  )
                )
              )
    (behavior "fetch calls the good callback after the timeout period has passed"
              (with-timeline
                (let [async-report (ar/new-async-report #() #() #())
                      localio (ls/new-local-storage async-report 100)
                      state (atom [])
                      goodfn (fn [data] (reset! state data))
                      badfn (fn [error] (reset! state error))
                      ]
                  (provided "when mocking setTimeout"
                            (js/setTimeout f n) =2=> (async n (f))
                            (aio/save localio "testuri" #() #() {:id "item1" :a 1})
                            (tick 100)
                            (aio/fetch localio "testuri" goodfn badfn "item1")
                            (behavior "nothing is called until after the simualed delay is passed"
                                      (is (= @state []))
                                      )
                            (behavior "fetch has happened when simualed delay is passed"
                                      (tick 100)
                                      (is (= (:a @state) 1))
                                      )
                            )
                  )
                )
              )
    (behavior "delete calls the good callback after the timeout period has passed"
              (with-timeline
                (let [async-report (ar/new-async-report #() #() #())
                      localio (ls/new-local-storage async-report 100)
                      state (atom [])
                      goodfn (fn [data] (reset! state data))
                      badfn (fn [error] (reset! state error))
                      ]
                  (provided "when mocking setTimeout"
                            (js/setTimeout f n) =2=> (async n (f))
                            (aio/save localio "testuri" #() #() {:id "item1" :a 1})
                            (tick 100)
                            (aio/delete localio "testuri" goodfn badfn "item1")
                            (behavior "nothing is called until after the simualed delay is passed"
                                      (is (= @state []))
                                      )
                            (behavior "item is deleted when simualed delay is passed"
                                      (tick 100)
                                      (is (= @state "item1"))
                                      )
                            )
                  )
                )
              )
    )
  )

