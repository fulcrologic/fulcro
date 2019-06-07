(ns com.fulcrologic.fulcro.server.config-spec
  (:require
    [clojure.test :as t :refer [are is]]
    [fulcro-spec.core :refer [specification assertions provided component behavior when-mocking]]
    [com.fulcrologic.fulcro.server.config :as server])
  (:import (java.io File)))

(declare =>)

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

(specification "load-config"
  (when-mocking
    (server/open-config-file d) =1x=> (do
                                        (assertions
                                          "Looks for the defaults file"
                                          d => "config/defaults.edn")
                                        {})
    (server/get-system-prop prop) => (do
                                       (assertions
                                         "usess the system property config"
                                         prop => "config")
                                       "some-file")
    (server/open-config-file f) =1x=> (do
                                        (assertions
                                          "to find the supplied config file"
                                          f => "some-file")
                                        {:k :v})

    (assertions
      "looks for system property -Dconfig"
      (server/load-config {}) => {:k :v}))

  (behavior "does not fail when returning nil"
    (assertions
      (server/get-system-prop "config") => nil))
  (behavior "defaults file is always used to provide missing values"
    (when-mocking
      (server/open-config-file defaults-path) =1x=> {:a :b}
      (server/open-config-file nil) =1x=> {:c :d}
      (assertions
        (server/load-config {}) => {:a :b
                                    :c :d})))

  (behavior "config file overrides defaults"
    (when-mocking
      (server/open-config-file defaults-path) =1x=> {:a {:b {:c :d}
                                                         :e {:z :v}}}
      (server/open-config-file nil) =1x=> {:a {:b {:c :f
                                                   :u :y}
                                               :e 13}}
      (assertions (server/load-config {}) => {:a {:b {:c :f
                                                      :u :y}
                                                  :e 13}})))

  (component "load-config"
    (behavior "crashes if no default is found"
      (assertions
        (server/load-config {}) =throws=> #""))
    (behavior "crashes if no config is found"
      (let [real-open server/open-config-file]
        (when-mocking
          (server/open-config-file defaults-path) =1x=> {}
          (server/open-config-file f) => (real-open f)
          (assertions (server/load-config {}) =throws=> #""))))
    (behavior "falls back to `config-path`"
      (when-mocking
        (server/open-config-file defaults-path) =1x=> {}
        (server/open-config-file "/some/path") =1x=> {:k :v}
        (assertions (server/load-config {:config-path "/some/path"}) => {:k :v})))
    (behavior "recursively resolves symbols using resolve-symbol"
      (when-mocking
        (server/open-config-file defaults-path) =1x=> {:a {:b {:c 'clojure.core/symbol}}
                                                       :v [0 "d"]
                                                       :s #{'clojure.core/symbol}}
        (server/open-config-file nil) =1x=> {}
        (assertions (server/load-config {}) => {:a {:b {:c #'clojure.core/symbol}}
                                                :v [0 "d"]
                                                :s #{#'clojure.core/symbol}})))
    (behavior "passes config-path to get-config"
      (when-mocking
        (server/open-config-file defaults-path) =1x=> {}
        (server/open-config-file "/foo/bar") =1x=> {}
        (assertions (server/load-config {:config-path "/foo/bar"}) => {})))
    (assertions
      "config-path can be a relative path"
      (server/load-config {:config-path "not/abs/path"})
      =throws=> #"Invalid config file"

      "prints the invalid path in the exception message"
      (server/load-config {:config-path "invalid/file"})
      =throws=> #"invalid/file"))

  (component "load-edn"
    (behavior "returns nil if absolute file is not found"
      (assertions (#'server/load-edn "/garbage") => nil))
    (behavior "returns nil if relative file is not on classpath"
      (assertions (#'server/load-edn "garbage") => nil))
    (behavior "can load edn from the classpath"
      (assertions (:some-key (#'server/load-edn "config/other.edn")) => :some-default-val))
    (behavior "can load edn with :env/vars"
      (when-mocking
        (server/get-system-env "FAKE_ENV_VAR") => "FAKE STUFF"
        (server/open-config-file defaults-path) =1x=> {}
        (server/get-system-prop "config") => :..cfg-path..
        (server/open-config-file :..cfg-path..) =1x=> {:fake :env/FAKE_ENV_VAR}
        (assertions (server/load-config) => {:fake "FAKE STUFF"}))
      (behavior "when the namespace is env.edn it will edn/read-string it"
        (when-mocking
          (server/get-system-env "FAKE_ENV_VAR") => "3000"
          (server/open-config-file defaults-path) =1x=> {}
          (server/get-system-prop "config") => :..cfg-path..
          (server/open-config-file :..cfg-path..) =1x=> {:fake :env.edn/FAKE_ENV_VAR}
          (assertions (server/load-config) => {:fake 3000}))
        (behavior "buyer beware as it'll parse it in ways you might not expect!"
          (when-mocking
            (server/get-system-env "FAKE_ENV_VAR") => "some-symbol"
            (server/open-config-file defaults-path) =1x=> {}
            (server/get-system-prop "config") => :..cfg-path..
            (server/open-config-file :..cfg-path..) =1x=> {:fake :env.edn/FAKE_ENV_VAR}
            (assertions (server/load-config) => {:fake 'some-symbol}))))))
  (component "open-config-file"
    (behavior "takes in a path, finds the file at that path and should return a clojure map"
      (when-mocking
        (server/load-edn "/foobar") => "42"
        (assertions
          (#'server/open-config-file "/foobar") => "42")))
    (behavior "or if path is nil, uses a default path"
      (assertions
        (#'server/open-config-file nil) =throws=> #"Invalid config file"))
    (behavior "if path doesn't exist on fs, it throws an ex-info"
      (assertions
        (#'server/open-config-file "/should/fail") =throws=> #"Invalid config file"))))


