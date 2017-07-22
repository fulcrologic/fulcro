(ns fulcro.server.core-spec
  (:require
    [clojure.test :as t]
    [com.stuartsierra.component :as component]
    [fulcro.server :as core]
    [fulcro.easy-server :as easy]
    [fulcro-spec.core :refer [specification behavior provided component assertions]])
  (:import (clojure.lang ExceptionInfo)))

(specification "transitive join"
  (behavior "Creates a map a->c from a->b combined with b->c"
    (assertions
      (core/transitive-join {:a :b} {:b :c}) => {:a :c})))

(specification "make-fulcro-server"
  (assertions
    "requires :parser to be a function"
    (easy/make-fulcro-server :parser 'cymbal) =throws=> (AssertionError #"fn?")
    "requires that :components be a map"
    (easy/make-fulcro-server :parser #() :components [1 2 3]) =throws=> (AssertionError #"map\? components")
    "throws an exception if injections are not keywords"
    (easy/make-fulcro-server :parser #() :parser-injections [:a :x 'sym]) =throws=> (AssertionError #"every\? keyword")))

(defrecord SimpleTestModule []
  core/Module
  (system-key [this] ::SimpleTestModule)
  (components [this] {}))
(defn make-simple-test-module []
  (component/using
    (map->SimpleTestModule {})
    []))

(defrecord DepTestModule [test-dep]
  core/Module
  (system-key [this] ::DepTestModule)
  (components [this]
    {:test-dep
     (or test-dep
         (reify
           component/Lifecycle
           (start [this] {:value "test-dep"})
           (stop [this] this)))})
  component/Lifecycle
  (start [this] (assoc this :value "DepTestModule"))
  (stop [this] this))
(defn make-dep-test-module [& [{:keys [test-dep]}]]
  (component/using
    (map->DepTestModule {:test-dep test-dep})
    [:test-dep]))

(defrecord TestApiModule [sys-key reads mutates cmps]
  core/Module
  (system-key [this] (or sys-key ::TestApiModule))
  (components [this] (or cmps {}))
  core/APIHandler
  (api-read [this]
    (if-not reads (constantly {:value :read/ok})
      (fn [env k ps]
        (when-let [value (get reads k)]
          {:value value}))))
  (api-mutate [this]
    (if-not mutates (constantly {:action (constantly :mutate/ok)})
      (fn [env k ps]
        (when-let [value (get mutates k)]
          {:action (constantly value)})))))
(defn make-test-api-module [& [opts]]
  (map->TestApiModule
    (select-keys opts
      [:reads :mutates :sys-key :cmps])))

(defn test-fulcro-system [opts]
  (component/start (core/fulcro-system opts)))
(specification "fulcro-system"
  (component ":api-handler-key - defines location in the system of the api handler"
    (assertions
      (test-fulcro-system {:api-handler-key ::here})
      =fn=> (fn [sys]
              (t/is (fn? (get-in sys [::here :middleware])))
              true)
      "defaults to :fulcro.server.core/api-handler"
      (test-fulcro-system {})
      =fn=> (fn [sys]
              (t/is (fn? (get-in sys [::core/api-handler :middleware])))
              true)))
  (component ":app-name - prefixes the /api route"
    (assertions
      (-> (test-fulcro-system {:app-name "asdf"})
        ::core/api-handler :middleware
        (#(% (constantly {:status 404}))))
      =fn=> (fn [h]
              (t/is (= {:status 404} (h {:uri "/api"})))
              (t/is (= 200 (:status (h {:uri "/asdf/api"}))))
              true)))
  (component ":components"
    (behavior "get put into the system as is"
      (assertions
        (test-fulcro-system
          {:components {:c1 (reify
                              component/Lifecycle
                              (start [this] {:value "c1"})
                              (stop [this] this))
                        :c2 {:test "c2"}}})
        =fn=> (fn [sys]
                (t/is (= #{::core/api-handler :c1 :c2} (set (keys sys))))
                (t/is (not-any? nil? (vals sys)))
                true))))
  (component ":modules - implement:"
    (component "Module (required)"
      (component "system-key"
        (assertions "is used to locate them in the system"
          (test-fulcro-system
            {:modules [(make-simple-test-module)]})
          =fn=> (fn [sys]
                  (t/is (not (nil? (get-in sys [::SimpleTestModule]))))
                  true)))

      (component "components"
        (assertions
          (core/components (make-dep-test-module)) =fn=> :test-dep
          "are sub components raised into the system"
          (test-fulcro-system
            {:modules [(make-dep-test-module)]})
          =fn=> (fn [sys]
                  (t/is (= "DepTestModule" (get-in sys [::DepTestModule :value])))
                  (t/is (= (get-in sys [::DepTestModule :test-dep])
                           (get-in sys [:test-dep])))
                  true)
          "they can have their own deps"
          (test-fulcro-system
            {:modules [(make-dep-test-module
                         {:test-dep (component/using {:test-dep "yeah"} [:dep2])})]
             :components {:dep2 {:value "dep2"}}})
          =fn=> (fn [sys]
                  (t/is (= (get-in sys [::DepTestModule :test-dep :dep2])
                           (get-in sys [:dep2])))
                  true))))
    (component "APIHandler (optional)"
      (behavior "is used to compose reads&mutates like (ring) middleware"
        (assertions
          (((get-in
              (test-fulcro-system
                {:modules [(make-test-api-module)]})
              [::core/api-handler :middleware])
            (constantly {:status 404}))
           {:uri "/api"
            :transit-params '[(launch-rocket!) :rocket-status]})
          => {:status 200
              :headers {"Content-Type" "application/transit+json"}
              :body {'launch-rocket! :mutate/ok
                     :rocket-status :read/ok}}
          "get executed in the order of :modules, you should return nil if you do not handle the dispatch-key"
          (((get-in
              (test-fulcro-system
                {:modules [(make-test-api-module
                             {:sys-key :always-working
                              :reads {:rocket-status :working
                                      :working :working/true}})
                           (make-test-api-module
                             {:sys-key :always-broken
                              :reads {:rocket-status :broken
                                      :broken :broken/true}})]})
              [::core/api-handler :middleware])
            (constantly {:status 404}))
           {:uri "/api"
            :transit-params '[:rocket-status :working :broken]})
          => {:status 200
              :headers {"Content-Type" "application/transit+json"}
              :body {:rocket-status :working
                     :working :working/true
                     :broken :broken/true}}))))
  (behavior "all system keys must be unique, (Module/system-key and Module/components keys)"
    (assertions
      (core/fulcro-system {:components {:foo {}}
                              :modules [(make-test-api-module
                                          {:sys-key :foo})]})
      =throws=> (ExceptionInfo #"(?i)duplicate.*:foo.*fulcro-system")
      (core/fulcro-system {:components {:foo {}}
                              :modules [(make-test-api-module
                                          {:cmps {:foo "test-api"}})]})
      =throws=> (ExceptionInfo #"(?i)duplicate.*:foo.*fulcro-system"
                  #(do (t/is
                         (= (ex-data %)  {:key :foo :prev-value {} :new-value "test-api"}))
                     true))
      (core/fulcro-system {:modules [(make-test-api-module
                                          {:sys-key :foo})
                                        (make-test-api-module
                                          {:sys-key :foo})]})
      =throws=> (ExceptionInfo #"(?i)duplicate.*:foo.*Module/system-key")
      (core/fulcro-system {:modules [(make-test-api-module
                                          {:sys-key :foo1
                                           :cmps {:foo "foo1"}})
                                        (make-test-api-module
                                          {:sys-key :foo2
                                           :cmps {:foo "foo2"}})]})
      =throws=> (ExceptionInfo #"(?i)duplicate.*:foo.*Module/components"))))
