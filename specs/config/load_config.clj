(ns config.load_config
  (:require [untangled.config.core :as cfg])
  (:use midje.sweet))

(facts :focused "untangled.config"
       (facts :focused "load-config"
              (fact :focused "recursively merges props into defaults"
                    (cfg/load-config {}) => {:a {:b {:c :f
                                                     :u :y}
                                                 :e 13}}

                    (provided
                      (#'cfg/get-defaults nil) => {:a {:b {:c :d}
                                                       :e {:z :v}}}
                      (#'cfg/get-props nil)    => {:a {:b {:c :f
                                                           :u :y}
                                                       :e 13}}
                      ))
              (fact :focused "can take a prop path argument"
                    (cfg/load-config {:props-path "/foo/bar"})
                    => {:foo :qux}

                    (provided
                      (#'cfg/get-defaults nil) => {:foo :qux}
                      (#'cfg/get-props "/foo/bar") => {}
                      )
                    )
              (fact :focused "can take a defaults path argument"
                    (cfg/load-config {:defaults-path "/foo/bar"})
                    => {:foo :bar}

                    (provided
                      (#'cfg/get-defaults "/foo/bar") => {:foo :qux}
                      (#'cfg/get-props nil) => {:foo :bar}
                      )
                    )
              )
       (facts :focused "find-file"
              (fact :focused "looks up the argument in the classpath"
                    (#'cfg/find-file "defaults.edn")
                    => "/Users/Anthony/projects/untangled-datomic-helpers/resources/defaults.edn"
                    )
              (fact :focused "or returns its argument if its an absolute path"
                    (#'cfg/find-file "/foo/bar") => "/foo/bar"
                    )
              )
       (facts :focused "get-props"
              (fact :focused "takes in a path, finds the file at that path and should return a clojure map"
                    (#'cfg/get-props "/foobar") => ..props..
                    (provided
                      (slurp "/foobar") => (str ..props..))
                    )
              (fact :focused "or if path is nil, uses a default path"
                    (#'cfg/get-props nil) => ..props..
                    (provided
                      (slurp "/usr/local/etc/config.edn") => (str ..props..))
                    )
              )
       (facts :focused "get-defaults"
              (fact :focused "takes in a path, finds the file at that path and should return a clojure map"
                    (#'cfg/get-defaults "/foobar") => ..defaults..
                    (provided
                      (slurp "/foobar") => (str ..defaults..))
                    )
              (fact :focused "or if path is nil, uses a default path"
                    (#'cfg/get-defaults nil) => ..defaults..
                    (provided
                      (slurp "/Users/Anthony/projects/untangled-datomic-helpers/resources/defaults.edn") => (str ..defaults..))
                    )
              )
       )

