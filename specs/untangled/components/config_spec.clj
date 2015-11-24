(ns untangled.components.config-spec
  (:require [com.stuartsierra.component :as component]
            [untangled.components.config :as cfg])
  (:use midje.sweet)
  (:import (java.io File)
           (clojure.lang ExceptionInfo)))

(defn with-tmp-edn-file
  "Creates a temporary edn file with stringified `contents`,
   calls `f` with its absolute path,
   and returns the result after deleting the file."
  [contents f]
  (let [tmp-file (File/createTempFile "tmp-file" ".edn")
        _ (spit tmp-file (str contents))
        abs-path (.getAbsolutePath tmp-file)
        res (f abs-path)]
    (.delete tmp-file) res))

(def defaults-path "config/defaults.edn")

(facts "untangled.config"
       (fact "defaults file is always used to provide missing values"
             (cfg/load-config {}) => {:a :b
                                      :c :d}
             (provided
               (#'cfg/get-defaults defaults-path) => {:a :b}
               (#'cfg/get-config nil) => {:c :d}))
       (fact "looks for system property -Dconfig"
             (cfg/load-config {}) => {:k :v}
             (provided
               (#'cfg/get-defaults defaults-path) => {}
               (#'cfg/get-system-prop "config") => ..file..
               (#'cfg/get-config ..file..) => {:k :v}))
       (fact "config file overrides defaults"
             (cfg/load-config {}) => {:a {:b {:c :f
                                              :u :y}
                                          :e 13}}
             (provided
               (#'cfg/get-defaults defaults-path) => {:a {:b {:c :d}
                                                          :e {:z :v}}}
               (#'cfg/get-config nil) => {:a {:b {:c :f
                                                  :u :y}
                                              :e 13}}))

       (facts "load-config"
              (fact "crashes if no default is found"
                    (cfg/load-config {}) => (throws ExceptionInfo))
              (fact "crashes if no config is found"
                    (cfg/load-config {}) => (throws ExceptionInfo)
                    (provided
                      (#'cfg/get-defaults defaults-path) => {}))
              (fact "falls back to `config-path`"
                    (cfg/load-config {:config-path "/some/path"}) => {:k :v}
                    (provided
                      (#'cfg/get-defaults defaults-path) => {}
                      (#'cfg/get-config "/some/path") => {:k :v}))
              (fact "recursively resolves symbols using resolve-symbol"
                    (cfg/load-config {}) => {:a {:b {:c #'clojure.core/symbol}}
                                             :v [0 "d"]
                                             :s #{#'clojure.core/symbol}}
                    (provided
                      (#'cfg/get-defaults defaults-path) => {:a {:b {:c 'clojure.core/symbol}}
                                                             :v [0 "d"]
                                                             :s #{'clojure.core/symbol}}
                      (#'cfg/get-config nil) => {}))
              (fact "passes config-path to get-config"
                    (cfg/load-config {:config-path "/foo/bar"}) => {}
                    (provided
                      (#'cfg/get-defaults defaults-path) => {}
                      (#'cfg/get-config "/foo/bar") => {}))
              (fact "config-path must be an absolute pathj"
                    (cfg/load-config {:config-path "not/abs/path"})
                    => (throws AssertionError #"startsWith.*\/"))
              (fact "passes defaults-path to get-defaults"
                    (cfg/load-config {:defaults-path "/foo/bar"}) => {}
                    (provided
                      (#'cfg/get-defaults "/foo/bar") => {}
                      (#'cfg/get-config nil) => {})))

       (facts "resolve-symbol"
              (fact "requires if necessary"
                    (#'cfg/resolve-symbol 'util.dont-require-me/stahp)
                    => false
                    (provided
                      (resolve 'util.dont-require-me/stahp) => false
                      (require 'util.dont-require-me) => anything))
              (fact "fails if require fails"
                    (#'cfg/resolve-symbol 'srsly/not-a-var)
                    => (throws java.io.FileNotFoundException))
              (fact "fails if not found in the namespace after requiring"
                    (#'cfg/resolve-symbol 'util.dont-require-me/invalid)
                    => (throws AssertionError #"not \(nil"))
              (fact "must be namespaced, throws otherwise"
                    (#'cfg/resolve-symbol 'invalid)
                    => (throws AssertionError #"namespace")))

       (facts "load-edn"
              (fact "returns nil if absolute file is not found"
                    (#'cfg/load-edn "/garbage") => nil)
              (fact "returns nil if relative file is not on classpath"
                    (#'cfg/load-edn "garbage") => nil)
              (fact "can load edn from the classpath"
                    (#'cfg/load-edn "resources/config/defaults.edn")
                    => (contains {:some-key :some-default-val}))
              (fact :integration "can load edn from the disk"
                    (with-tmp-edn-file {:foo :bar} #'cfg/load-edn)
                    => {:foo :bar})
              (fact :integration "can load edn with symbols"
                    (with-tmp-edn-file {:sym 'sym} #'cfg/load-edn)
                    => {:sym 'sym}))

       (facts "open-config-file"
              (fact "takes in a path, finds the file at that path and should return a clojure map"
                    (#'cfg/open-config-file "/foobar") => ..config..
                    (provided
                      (#'cfg/load-edn "/foobar") => ..config..))
              (fact "or if path is nil, uses a default path"
                    (#'cfg/open-config-file nil)
                    => (throws ExceptionInfo #"provide a valid file"))
              (fact "if path doesn't exist on fs, it throws an ex-info"
                    (#'cfg/get-config "/should/fail")
                    => (throws ExceptionInfo #"provide a valid file"))))

(defrecord App []
  component/Lifecycle
  (start [this] this)
  (stop [this] this))

(defn new-app []
  (component/using
    (map->App {})
    [:config]))

(facts "untangled.components.config"
       (facts "new-config"
              (fact "returns a stuartsierra component"
                    (satisfies? component/Lifecycle (cfg/new-config "w/e")) => true
                    (fact ".start loads the config"
                          (.start (cfg/new-config "mocked-out")) => (contains {:value ..cfg..})
                          (provided
                            (cfg/load-config anything) => ..cfg..))
                    (fact ".stop removes the config"
                          (-> (cfg/new-config "mocked-out") .start .stop :config) => nil
                          (provided
                            (cfg/load-config anything) => anything))))

       (facts "new-config can be injected through a system-map"
              (-> (component/system-map
                    :config (cfg/new-config "mocked-out")
                    :app (new-app))
                  .start :app :config :value) => {:foo :bar}
              (provided
                (cfg/load-config anything) => {:foo :bar})))
