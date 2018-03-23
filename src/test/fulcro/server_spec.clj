(ns fulcro.server-spec
  (:require [fulcro-spec.core :refer [specification assertions provided component behavior when-mocking]]
            [clojure.test :as t]
            [fulcro.server :as server :refer [augment-response]]
            [fulcro.easy-server :as easy :refer [make-web-server]]
            [org.httpkit.server :refer [run-server]]
            [com.stuartsierra.component :as component]
            [fulcro.client.primitives :as prim]
            [clojure.spec.alpha :as s]
            [clojure.set :as set]
            [fulcro.logging :as log])
  (:import (clojure.lang ExceptionInfo)
           (java.io File)))

(specification "transitive join"
  (behavior "Creates a map a->c from a->b combined with b->c"
    (assertions
      (server/transitive-join {:a :b} {:b :c}) => {:a :c})))

(t/use-fixtures
  :once #(do
           (log/set-level! :fatal)
           (%)
           (log/set-level! :info)))

(specification "generate-response"
  (assertions
    "returns a map with status, header, and body."
    (keys (server/generate-response {})) => [:status :body :headers]

    "merges Content-Type of transit json to the passed-in headers."
    (:headers (server/generate-response {:headers {:my :header}})) => {:my            :header
                                                                       "Content-Type" "application/transit+json"}

    "preserves extra response keys from input"
    (server/generate-response {:status 200 :body {} :session {:user-id 123}})
    => {:status  200
        :headers {"Content-Type" "application/transit+json"}
        :body    {}
        :session {:user-id 123}}))


(defn run [handler req]
  ((:middleware (component/start handler)) req))

(specification "Raw API Request Handling (primary components for hand-building server)"
  (component "handle-api-request"
    (let [parser-return-value {'f {:result {:value 1}}}
          parser              (constantly parser-return-value)
          mock-response       {'f {:value 1}}
          tx                  []
          env                 {}]
      (provided "The parser returns a completely valid response"
        ; when-mocking will check number of times. Order is inferred by joining return from prior to params of latter
        (server/raise-response resp) =1x=> (do
                                             (behavior "Uses raise-response"
                                               (assertions
                                                 "raise-response receives the output of the parser"
                                                 resp => parser-return-value))
                                             mock-response)
        (server/valid-response? resp) =1x=> (do
                                              (behavior "Uses valid-response? to check validity"
                                                (assertions
                                                  "valid-response? checks the raised response"
                                                  resp => mock-response))
                                              true)
        (server/augment-map resp) =1x=> (do
                                          (behavior "Augments the response"
                                            (assertions
                                              "augment-map works on the raised response"
                                              resp => mock-response
                                              "Uses augment-map to update modify response for Ring"
                                              true => true))
                                          {:extra-key 22})

        (let [response (server/handle-api-request parser env tx)]

          (behavior "The Ring response from the request"
            (assertions
              "has transit as the encoding"
              (get-in response [:headers "Content-Type"]) => "application/transit+json"
              "has a status code of 200"
              (:status response) => 200
              "has the EDN body from the raised response"
              (:body response) => mock-response
              "Includes anything that the augments added"
              (:extra-key response) => 22)))))
    (let [mutation-exception        (Exception.)
          parser                    (fn [env query] (throw mutation-exception))
          tx                        []
          env                       {}
          server-exception-response {:status 500 :body "EXCEPTION"}]
      (provided "The parser throws an exception"
        ; when-mocking will check number of times. Order is inferred by joining return from prior to params of latter
        (server/valid-response? resp) =1x=> (do
                                              (behavior "Uses valid-response? to check validity"
                                                (assertions
                                                  "valid-response? is given the exception (returns false)"
                                                  resp => mutation-exception))
                                              false)
        (server/process-errors resp) =1x=> (do
                                             (behavior "process-errors"
                                               (assertions
                                                 "is given the exception in order to generate the server error response"
                                                 resp => mutation-exception))
                                             server-exception-response)

        (let [response (server/handle-api-request parser env tx)]

          (behavior "The Ring response from the request"
            (assertions
              "Still has the proper content type"
              (get-in response [:headers "Content-Type"]) => "application/transit+json"
              "Has the status code from the error processing"
              (:status response) => (:status server-exception-response)
              "Has a body from the error processing"
              (:body response) => (:body server-exception-response)))))))
  (component "raise-response"
    (let [oldid (prim/tempid)
          newid 42]
      (assertions
        "raises the :result value to the top"
        (server/raise-response {'f {:result {:x 1}}}) => {'f {:x 1}}
        "carries tempids up as well"
        (server/raise-response {'f {:result {:tempids {oldid newid}
                                             :x       1}}}) => {'f {:x       1
                                                                    :tempids {oldid newid}}})))
  (component "valid-response?"
    (assertions
      "returns false if the response was an exception"
      (server/valid-response? (Exception.)) => false
      "returns false if any of the mutations return a value that has ::prim/error"
      (server/valid-response? {'f {} 'g {::prim/error 1}}) => false
      "returns true for mutation responses that return normal data"
      (server/valid-response? {'f {} 'g {}}) => true))
  (component "Augmenting Responses (Working with Ring Responses from Mutations and Queries)"
    (let [my-read      (fn [_ key _]
                         {:value (case key
                                   :foo "success"
                                   :foo-session (augment-response {:some "data"} #(assoc-in % [:session :uid] "current-user"))
                                   :bar (throw (ex-info "Oops" {:my :bad}))
                                   :bar' (throw (ex-info "Oops'" {:status 402 :body "quite an error"}))
                                   :baz (throw (IllegalArgumentException.)))})

          my-mutate    (fn [_ key params]
                         {:action (condp = key
                                    'foo (fn [] "success")
                                    'overrides (fn [] (augment-response {} #(assoc % :body "override"
                                                                                     :status 201
                                                                                     :cookies {:uid (:uid params)})))
                                    'bar (fn [] (throw (ex-info "Oops" {:my :bad})))
                                    'bar' (fn [] (throw (ex-info "Oops'" {:status 402 :body "quite an error"})))
                                    'baz (fn [] (throw (IllegalArgumentException.))))})

          parser       (server/parser {:read my-read :mutate my-mutate})
          parse-result (fn [query] (server/handle-api-request parser {} query))]

      (behavior "for reads"
        (behavior "for a valid request"
          (behavior "returns a query response"
            (let [result (parse-result [:foo])]
              (assertions
                "with a body containing the expected parse result."
                (:body result) => {:foo "success"}))))

        (behavior "for an invalid request"
          (behavior "when the parser generates an expected error"
            (let [result (parse-result [:bar'])]
              (assertions
                "returns a status code."
                (:status result) =fn=> (complement nil?)

                "returns body if provided."
                (:body result) => "quite an error")))

          (behavior "when the parser generates an unexpected error"
            (let [result (parse-result [:bar])]
              (assertions
                "returns a 500 http status code."
                (:status result) => 500

                "contains an exception in the response body."
                (:body result) => {:type "class clojure.lang.ExceptionInfo" :message "Oops" :data {:my :bad}})))

          (behavior "when the parser does not generate the error"
            (let [result (parse-result [:baz])]
              (assertions
                "returns a 500 http status code."
                (:status result) => 500

                "returns exception data in the response body."
                (:body result) => {:type "class java.lang.IllegalArgumentException", :message nil})))))

      (behavior "for mutates"
        (behavior "for a valid request"
          (behavior "returns a query response"
            (let [result (parse-result ['(foo)])]
              (assertions
                "with a body containing the expected parse result."
                (:body result) => {'foo "success"}))))

        (behavior "for invalid requests (where one or more mutations fail)"
          (let [bar-result  (parse-result ['(bar')])
                bar'-result (parse-result ['(bar)])
                baz-result  (parse-result ['(baz)])]

            (behavior "returns a status code of 400."
              (doall (map #(t/is (= 400 (:status %))) [bar'-result bar-result baz-result])))

            (behavior "returns failing mutation result in the body."
              (letfn [(get-error [result] (-> result :body vals first :fulcro.client.primitives/error))]
                (assertions
                  (get-error bar-result) => {:type    "class clojure.lang.ExceptionInfo",
                                             :message "Oops'",
                                             :data    {:status 402, :body "quite an error"}}

                  (get-error bar'-result) => {:type    "class clojure.lang.ExceptionInfo",
                                              :message "Oops",
                                              :data    {:my :bad}}

                  (get-error baz-result) => {:type    "class java.lang.IllegalArgumentException",
                                             :message nil}))))))

      (behavior "for updating the response"
        (behavior "adds the response keys to the ring response"
          (let [result (parse-result [:foo-session])]
            (assertions
              (:session result) => {:uid "current-user"})))
        (behavior "user can override response fields, eg status & body"
          (assertions
            (parse-result ['(overrides {:uid "new-user"})])
            => {:status  201, :body "override", :cookies {:uid "new-user"}
                :headers {"Content-Type" "application/transit+json"}}))))))

(def dflt-cfg {:port 1337})

(defn with-tmp-edn-file
  "Creates a temporary edn file with stringified `contents`,
   calls `f` with its absolute path,
   and returns the result after deleting the file."
  [contents f]
  (let [tmp-file (File/createTempFile "tmp-file" ".edn")
        _        (spit tmp-file (str contents))
        abs-path (.getAbsolutePath tmp-file)
        res      (f abs-path)]
    (.delete tmp-file) res))

(def defaults-path "config/defaults.edn")

(specification "fulcro.server/config"
  (when-mocking
    (server/get-defaults defaults-path) => {}
    (server/get-system-prop "config") => "some-file"
    (server/get-config "some-file") => {:k :v}

    (behavior "looks for system property -Dconfig"
      (assertions
        (server/load-config {}) => {:k :v})))

  (behavior "does not fail when returning nil"
    (assertions
      (#'server/get-system-prop "config") => nil))
  (behavior "defaults file is always used to provide missing values"
    (when-mocking
      (server/get-defaults defaults-path) => {:a :b}
      (server/get-config nil) => {:c :d}
      (assertions
        (server/load-config {}) => {:a :b
                                    :c :d})))

  (behavior "config file overrides defaults"
    (when-mocking
      (server/get-defaults defaults-path) => {:a {:b {:c :d}
                                                  :e {:z :v}}}
      (server/get-config nil) => {:a {:b {:c :f
                                          :u :y}
                                      :e 13}}
      (assertions (server/load-config {}) => {:a {:b {:c :f
                                                      :u :y}
                                                  :e 13}})))

  (component "load-config"
    (behavior "crashes if no default is found"
      (assertions
        (server/load-config {}) =throws=> (ExceptionInfo #"")))
    (behavior "crashes if no config is found"
      (when-mocking
        (server/get-defaults defaults-path) => {}
        (assertions (server/load-config {}) =throws=> (ExceptionInfo #""))))
    (behavior "falls back to `config-path`"
      (when-mocking
        (server/get-defaults defaults-path) => {}
        (server/get-config "/some/path") => {:k :v}
        (assertions (server/load-config {:config-path "/some/path"}) => {:k :v})))
    (behavior "recursively resolves symbols using resolve-symbol"
      (when-mocking
        (server/get-defaults defaults-path) => {:a {:b {:c 'clojure.core/symbol}}
                                                :v [0 "d"]
                                                :s #{'clojure.core/symbol}}
        (server/get-config nil) => {}
        (assertions (server/load-config {}) => {:a {:b {:c #'clojure.core/symbol}}
                                                :v [0 "d"]
                                                :s #{#'clojure.core/symbol}})))
    (behavior "passes config-path to get-config"
      (when-mocking
        (server/get-defaults defaults-path) => {}
        (server/get-config "/foo/bar") => {}
        (assertions (server/load-config {:config-path "/foo/bar"}) => {})))
    (assertions
      "config-path can be a relative path"
      (server/load-config {:config-path "not/abs/path"})
      =throws=> (ExceptionInfo #"Invalid config file")

      "prints the invalid path in the exception message"
      (server/load-config {:config-path "invalid/file"})
      =throws=> (ExceptionInfo #"invalid/file")))

  (component "resolve-symbol"
    (behavior "requires if necessary"
      (when-mocking
        (resolve 'fulcro.server.fixtures.dont-require-me/stahp) => false
        (require 'fulcro.server.fixtures.dont-require-me) => true
        (assertions (#'server/resolve-symbol 'fulcro.server.fixtures.dont-require-me/stahp) => false)))
    (behavior "fails if require fails"
      (assertions
        (#'server/resolve-symbol 'srsly/not-a-var) =throws=> (java.io.FileNotFoundException #"")))
    (behavior "if not found in the namespace after requiring"
      (assertions
        (#'server/resolve-symbol 'fulcro.server.fixtures.dont-require-me/invalid) =throws=> (AssertionError #"not \(nil")))
    (behavior "must be namespaced, throws otherwise"
      (assertions
        (#'server/resolve-symbol 'invalid) =throws=> (AssertionError #"namespace"))))

  (component "load-edn"
    (behavior "returns nil if absolute file is not found"
      (assertions (#'server/load-edn "/garbage") => nil))
    (behavior "returns nil if relative file is not on classpath"
      (assertions (#'server/load-edn "garbage") => nil))
    (behavior "can load edn from the classpath"
      (assertions (:some-key (#'server/load-edn "resources/config/defaults.edn")) => :some-default-val))
    (behavior "can load edn with :env/vars"
      (when-mocking
        (server/get-system-env "FAKE_ENV_VAR") => "FAKE STUFF"
        (server/get-defaults defaults-path) => {}
        (server/get-system-prop "config") => :..cfg-path..
        (server/get-config :..cfg-path..) => {:fake :env/FAKE_ENV_VAR}
        (assertions (server/load-config) => {:fake "FAKE STUFF"}))
      (behavior "when the namespace is env.edn it will edn/read-string it"
        (when-mocking
          (server/get-system-env "FAKE_ENV_VAR") => "3000"
          (server/get-defaults defaults-path) => {}
          (server/get-system-prop "config") => :..cfg-path..
          (server/get-config :..cfg-path..) => {:fake :env.edn/FAKE_ENV_VAR}
          (assertions (server/load-config) => {:fake 3000}))
        (behavior "buyer beware as it'll parse it in ways you might not expect!"
          (when-mocking
            (server/get-system-env "FAKE_ENV_VAR") => "some-symbol"
            (server/get-defaults defaults-path) => {}
            (server/get-system-prop "config") => :..cfg-path..
            (server/get-config :..cfg-path..) => {:fake :env.edn/FAKE_ENV_VAR}
            (assertions (server/load-config) => {:fake 'some-symbol}))))))
  (component "open-config-file"
    (behavior "takes in a path, finds the file at that path and should return a clojure map"
      (when-mocking
        (server/load-edn "/foobar") => "42"
        (assertions
          (#'server/open-config-file "/foobar") => "42")))
    (behavior "or if path is nil, uses a default path"
      (assertions
        (#'server/open-config-file nil) =throws=> (ExceptionInfo #"Invalid config file")))
    (behavior "if path doesn't exist on fs, it throws an ex-info"
      (assertions
        (#'server/get-config "/should/fail") =throws=> (ExceptionInfo #"Invalid config file")))))

(defrecord App []
  component/Lifecycle
  (start [this] this)
  (stop [this] this))

(defn new-app []
  (component/using
    (map->App {})
    [:config]))

(specification "fulcro.server/config implementation details"
  (component "new-config"
    (behavior "returns a stuartsierra component"
      (assertions (satisfies? component/Lifecycle (server/new-config "w/e")) => true)
      (behavior ".start loads the config"
        (when-mocking
          (server/load-config _) => "42"
          (assertions (:value (.start (server/new-config "mocked-out"))) => "42")))
      (behavior ".stop removes the config"
        (when-mocking
          (server/load-config _) => "wateva"
          (assertions (-> (server/new-config "mocked-out") .start .stop :config) => nil)))))

  (behavior "new-config can be injected through a system-map"
    (when-mocking
      (server/load-config _) => {:foo :bar}
      (assertions
        (-> (component/system-map
              :config (server/new-config "mocked-out")
              :app (new-app)) .start :app :config :value) => {:foo :bar})))

  (behavior "raw-config creates a config with the passed value"
    (assertions (-> (component/system-map
                      :config (server/raw-config {:some :config})
                      :app (new-app))
                  .start :app :config :value) => {:some :config})))
