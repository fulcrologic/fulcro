(ns fulcro.server-helpers-spec
  (:require [fulcro-spec.core :refer [specification assertions provided component behavior when-mocking]]
            [clojure.test :as t]
            [fulcro.server :as server :refer [augment-response]]
            [fulcro.easy-server :as easy :refer [make-web-server]]
            [fulcro.modular-server :as mserver]
            [org.httpkit.server :refer [run-server]]
            [com.stuartsierra.component :as component]
            [fulcro.logging :as log])
  (:import (clojure.lang ExceptionInfo)
           (java.io File)))

(specification "transitive join"
  (behavior "Creates a map a->c from a->b combined with b->c"
    (assertions
      (server/transitive-join {:a :b} {:b :c}) => {:a :c})))

(specification "make-fulcro-server"
  (assertions
    "requires :parser to be a function"
    (easy/make-fulcro-server :parser 'cymbal) =throws=> (AssertionError #"fn?")
    "requires that :components be a map"
    (easy/make-fulcro-server :parser #() :components [1 2 3]) =throws=> (AssertionError #"map\? components")
    "throws an exception if injections are not keywords"
    (easy/make-fulcro-server :parser #() :parser-injections [:a :x 'sym]) =throws=> (AssertionError #"every\? keyword")))

(defrecord SimpleTestModule []
  mserver/Module
  (system-key [this] ::SimpleTestModule)
  (components [this] {}))
(defn make-simple-test-module []
  (component/using
    (map->SimpleTestModule {})
    []))

(defrecord DepTestModule [test-dep]
  mserver/Module
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
  mserver/Module
  (system-key [this] (or sys-key ::TestApiModule))
  (components [this] (or cmps {}))
  mserver/APIHandler
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
  (component/start (mserver/fulcro-system opts)))

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
              (t/is (fn? (get-in sys [::mserver/api-handler :middleware])))
              true)))
  (component ":app-name - prefixes the /api route"
    (assertions
      (-> (test-fulcro-system {:app-name "asdf"})
        ::mserver/api-handler :middleware
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
                (t/is (= #{::mserver/api-handler :c1 :c2} (set (keys sys))))
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
          (mserver/components (make-dep-test-module)) =fn=> :test-dep
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
            {:modules    [(make-dep-test-module
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
              [::mserver/api-handler :middleware])
             (constantly {:status 404}))
            {:uri            "/api"
             :transit-params '[(launch-rocket!) :rocket-status]})
          => {:status  200
              :headers {"Content-Type" "application/transit+json"}
              :body    {'launch-rocket! :mutate/ok
                        :rocket-status  :read/ok}}
          "get executed in the order of :modules, you should return nil if you do not handle the dispatch-key"
          (((get-in
              (test-fulcro-system
                {:modules [(make-test-api-module
                             {:sys-key :always-working
                              :reads   {:rocket-status :working
                                        :working       :working/true}})
                           (make-test-api-module
                             {:sys-key :always-broken
                              :reads   {:rocket-status :broken
                                        :broken        :broken/true}})]})
              [::mserver/api-handler :middleware])
             (constantly {:status 404}))
            {:uri            "/api"
             :transit-params '[:rocket-status :working :broken]})
          => {:status  200
              :headers {"Content-Type" "application/transit+json"}
              :body    {:rocket-status :working
                        :working       :working/true
                        :broken        :broken/true}}))))
  (behavior "all system keys must be unique, (Module/system-key and Module/components keys)"
    (assertions
      (mserver/fulcro-system {:components {:foo {}}
                             :modules    [(make-test-api-module
                                            {:sys-key :foo})]})
      =throws=> (ExceptionInfo #"(?i)duplicate.*:foo.*fulcro-system")
      (mserver/fulcro-system {:components {:foo {}}
                             :modules    [(make-test-api-module
                                            {:cmps {:foo "test-api"}})]})
      =throws=> (ExceptionInfo #"(?i)duplicate.*:foo.*fulcro-system"
                  #(do (t/is
                         (= (ex-data %) {:key :foo :prev-value {} :new-value "test-api"}))
                       true))
      (mserver/fulcro-system {:modules [(make-test-api-module
                                         {:sys-key :foo})
                                       (make-test-api-module
                                         {:sys-key :foo})]})
      =throws=> (ExceptionInfo #"(?i)duplicate.*:foo.*Module/system-key")
      (mserver/fulcro-system {:modules [(make-test-api-module
                                         {:sys-key :foo1
                                          :cmps    {:foo "foo1"}})
                                       (make-test-api-module
                                         {:sys-key :foo2
                                          :cmps    {:foo "foo2"}})]})
      =throws=> (ExceptionInfo #"(?i)duplicate.*:foo.*Module/components"))))

(t/use-fixtures
  :once #(do
           (log/set-level! :fatal)
           (%)
           (log/set-level! :info)))

(defn run [handler req]
  ((:middleware (component/start handler)) req))

(specification "the handler"
  (behavior "takes an extra-routes map containing bidi :routes & :handlers"
    (let [make-handler (fn [extra-routes]
                         (easy/build-handler (constantly nil) #{}
                           :extra-routes extra-routes))]
      (assertions
        (-> {:routes   ["/" {"test" :test}]
             :handlers {:test (fn [env match]
                                {:body   "test"
                                 :status 200})}}
          (make-handler)
          (run {:uri "/test"}))
        => {:body    "test"
            :headers {"Content-Type" "application/octet-stream"}
            :status  200}

        "handler functions get passed the bidi match as an arg"
        (-> {:routes   ["/" {["test/" :id] :test-with-params}]
             :handlers {:test-with-params (fn [env match]
                                            {:body   (:id (:route-params match))
                                             :status 200})}}
          (make-handler)
          (run {:uri "/test/foo"}))
        => {:body    "foo"
            :status  200
            :headers {"Content-Type" "application/octet-stream"}}

        "and the request in the environment"
        (-> {:routes   ["/" {["test"] :test}]
             :handlers {:test (fn [env match]
                                {:body   {:req (:request env)}
                                 :status 200})}}
          (make-handler)
          (run {:uri "/test"}))
        => {:body    {:req {:uri "/test"}}
            :status  200
            :headers {"Content-Type" "application/octet-stream"}}

        "also dispatches on :request-method"
        (-> {:routes   ["/" {["test/" :id] {:post :test-post}}]
             :handlers {:test-post (fn [env match]
                                     {:body   "post"
                                      :status 200})}}
          (make-handler)
          (run {:uri            "/test/foo"
                :request-method :post}))
        => {:body    "post"
            :headers {"Content-Type" "application/octet-stream"}
            :status  200}

        "has to at least take a valid (but empty) :routes & :handlers"
        (-> {:routes ["" {}], :handlers {}}
          make-handler
          (run {:uri "/"})
          (dissoc :body))
        => {:headers {"Content-Type" "text/html"}
            :status  200})))

  (behavior "calling (get/set)-(pre/fallback)-hook can modify the ring handler stack"
    (letfn [(make-test-system []
              (.start (component/system-map
                        :config {}
                        :logger {}
                        :handler (easy/build-handler (constantly nil) {}))))]
      (assertions
        "the pre-hook which can short-circuit before the extra-routes, wrap-resource, or /api"
        (let [{:keys [handler]} (make-test-system)]
          (easy/set-pre-hook! handler (fn [h]
                                        (fn [req] {:status  200
                                                   :headers {"Content-Type" "text/text"}
                                                   :body    "pre-hook"})))

          (:body ((:middleware handler) {})))
        => "pre-hook"

        "the fallback hook will only get called if all other handlers do nothing"
        (let [{:keys [handler]} (make-test-system)]
          (easy/set-fallback-hook! handler (fn [h]
                                             (fn [req] {:status  200
                                                        :headers {"Content-Type" "text/text"}
                                                        :body    "fallback-hook"})))
          (:body ((:middleware handler) {:uri "/i/should/fail"})))
        => "fallback-hook"

        "get-(pre/fallback)-hook returns whatever hook is currently installed"
        (let [{:keys [handler]} (make-test-system)]
          (easy/set-pre-hook! handler (fn [h] '_))
          (easy/get-pre-hook handler))
        =fn=> #(= '_ (%1 nil))
        (let [{:keys [handler]} (make-test-system)]
          (easy/set-fallback-hook! handler (fn [h] '_))
          (easy/get-fallback-hook handler))
        =fn=> #(= '_ (%1 nil))))))

(def dflt-cfg {:port 1337})

(defn make-test-system
  ([] (make-test-system dflt-cfg))
  ([cfg]
   (component/system-map
     :config {:value cfg}
     :handler {:middleware :fake/all-routes}
     :web-server (make-web-server :handler))))

(specification "WebServer"
  (component "start"
    (behavior "correctly grabs the port & all-routes, and returns the started server under :server"
      (when-mocking
        (run-server :fake/all-routes {:port 1337}) => :ok
        (assertions
          (-> (make-test-system) .start :web-server :server) => :ok)))
    (behavior "only allows http-kit-opts to be passed to the server"
      (let [ok-cfg     (zipmap easy/http-kit-opts (mapv (constantly 42) easy/http-kit-opts))
            ok-cfg+bad (merge ok-cfg {:not-in/http-kit-opts :bad/value})]
        (when-mocking
          (run-server :fake/all-routes opts) => (do (assertions opts => ok-cfg) :ok)
          (assertions
            (-> (make-test-system ok-cfg+bad) .start :web-server :server) => :ok))))))
