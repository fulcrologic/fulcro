(ns fulcro.client.logging-spec
  (:require
    [fulcro-spec.core :refer-macros [specification behavior assertions when-mocking]]
    [goog.debug.Logger.Level :as level]
    [goog.log :as glog]
    [fulcro.client.primitives :refer [*logger*]]
    [fulcro.client.logging :as log]))

(specification "Logging Level"
  (behavior "can be set to"
    (when-mocking
      (level/getPredefinedLevel name) =1x=> (assertions "all" name => "ALL")
      (level/getPredefinedLevel name) =1x=> (assertions "debug" name => "FINE")
      (level/getPredefinedLevel name) =1x=> (assertions "info" name => "INFO")
      (level/getPredefinedLevel name) =1x=> (assertions "warn" name => "WARNING")
      (level/getPredefinedLevel name) =1x=> (assertions "error" name => "SEVERE")
      (level/getPredefinedLevel name) =1x=> (assertions "none" name => "OFF")

      (doall (map log/set-level [:all :debug :info :warn :error :none])))))

(specification "Debug logging"
  (when-mocking
    (glog/fine *logger* _) => nil

    (assertions
      "Returns provided value after logging"
      (log/debug [:foo :bar]) => [:foo :bar]
      "Returns provided value after logging with a message"
      (log/debug "A message" [:foo :bar]) => [:foo :bar])))
