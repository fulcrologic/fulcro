(ns untangled.components.config-spec
  (:require [com.stuartsierra.component :as component]
            [untangled.components.config :as cfg])
  (:use midje.sweet)
  (:import (java.io File)))

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

(facts :focused "untangled.config"
       (facts :focused "load-config"
              (fact :focused "recursively merges config into defaults"
                    (cfg/load-config {}) => {:a {:b {:c :f
                                                     :u :y}
                                                 :e 13}}
                    (provided
                      (#'cfg/get-defaults nil) => {:a {:b {:c :d}
                                                       :e {:z :v}}}
                      (#'cfg/get-config nil) => {:a {:b {:c :f
                                                         :u :y}
                                                     :e 13}}))
              (fact :focused "can take a prop path argument"
                    (cfg/load-config {:config-path "/foo/bar"}) => {:foo :qux}
                    (provided
                      (#'cfg/get-defaults nil) => {:foo :qux}
                      (#'cfg/get-config "/foo/bar") => {}
                      )
                    )
              (fact :focused "can take a defaults path argument"
                    (cfg/load-config {:defaults-path "/foo/bar"}) => {:foo :bar}
                    (provided
                      (#'cfg/get-defaults "/foo/bar") => {:foo :qux}
                      (#'cfg/get-config nil) => {:foo :bar}))
              )
       (facts :focused "load-edn"
              (fact :focused "returns nil if absolute file is not found"
                    (#'cfg/load-edn "/garbage") => nil
                    )
              (fact :focused "returns nil if relative file is not on classpath"
                    (#'cfg/load-edn "garbage") => nil
                    )
              (fact :focused "can load edn from the classpath"
                    (#'cfg/load-edn "resources/defaults.edn")
                    => (contains {:some-key :some-default-val})
                    )
              (fact :integration :focused "can load edn from the disk"
                    (with-tmp-edn-file {:foo :bar} #'cfg/load-edn)
                    => {:foo :bar})
              (fact :integration :focused "can load edn with symbols"
                    (with-tmp-edn-file {:sym 'sym} #'cfg/load-edn)
                    => {:sym 'sym})
              )
       (facts :focused "get-config"
              (fact :focused "takes in a path, finds the file at that path and should return a clojure map"
                    (#'cfg/get-config "/foobar") => ..config..
                    (provided
                      (#'cfg/load-edn "/foobar") => ..config..)
                    )
              (fact :focused "or if path is nil, uses a default path"
                    (#'cfg/get-config nil) => ..config..
                    (provided
                      (#'cfg/load-edn cfg/fallback-config-path) => ..config..)
                    )
              (fact :focused "if path doesn't exist on fs, it throws an ex-info"
                    (#'cfg/get-config "/should/fail") => (throws clojure.lang.ExceptionInfo)
                    )
              )
       (facts :focused "get-defaults"
              (fact :focused "takes in a path, finds the file at that path and should return a clojure map"
                    (#'cfg/get-defaults "/foobar") => ..defaults..
                    (provided
                      (#'cfg/load-edn "/foobar") => ..defaults..)
                    )
              (fact :focused "or if path is nil, uses a default path"
                    (#'cfg/get-defaults nil) => ..defaults..
                    (provided
                      (#'cfg/load-edn cfg/fallback-defaults-path) => ..defaults..)
                    )
              (fact :focused "if path doesn't exist on fs, it throws an ex-info"
                    (#'cfg/get-defaults "/should/fail") => (throws clojure.lang.ExceptionInfo))
              )
       )

(defrecord App []
  component/Lifecycle
  (start [this] this)
  (stop [this] this))

(defn new-app []
  (component/using
    (map->App {})
    [:config]))

(facts :focused "untangled.components.config"
       (facts :focused "new-config"
              (fact :focused "returns a stuartsierra component"
                    (satisfies? component/Lifecycle (cfg/new-config)) => true

                    (fact :focused ".start loads the config"
                          (.start (cfg/new-config)) => (contains {:value ..cfg..})
                          (provided
                            (cfg/load-config anything) => ..cfg..)
                          )
                    (fact :focused ".stop removes the config"
                          (-> (cfg/new-config) .start .stop :config) => nil
                          (provided
                            (cfg/load-config anything) => anything)
                          )
                    )
              )
       (facts :focused "new-config can be injected through a system-map"
              (-> (component/system-map
                    :config (cfg/new-config)
                    :app (new-app))
                  .start :app :config :value) => {:foo :bar}
              (provided
                (cfg/load-config anything) => {:foo :bar})
              )
       )
