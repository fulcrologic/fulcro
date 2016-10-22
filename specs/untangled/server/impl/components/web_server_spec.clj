(ns untangled.server.impl.components.web-server-spec
  (:require [untangled-spec.core :refer
             [specification component behavior assertions when-mocking provided]]
            [com.stuartsierra.component :as component]
            [untangled.server.impl.components.web-server :as src]
            [untangled.server.core :refer [make-web-server]]
            [org.httpkit.server :refer [run-server]]
            [taoensso.timbre :as timbre]
            [clojure.test :as t]))

(t/use-fixtures
  :once #(timbre/with-merged-config
           {:ns-blacklist ["untangled.server.impl.components.web-server"]}
           (%)))

(def dflt-cfg {:port 1337})

(defn make-test-system
  ([] (make-test-system dflt-cfg))
  ([cfg]
   (component/system-map
     :config {:value cfg}
     :handler {:middleware :fake/all-routes}
     :web-server (make-web-server))))

(specification "WebServer"
  (component "start"
    (behavior "correctly grabs the port & all-routes, and returns the started server under :server"
      (when-mocking
        (run-server :fake/all-routes {:port 1337}) => :ok
        (assertions
          (-> (make-test-system) .start :web-server :server) => :ok)))
    (behavior "only allows http-kit-opts to be passed to the server"
      (let [ok-cfg (zipmap src/http-kit-opts (mapv (constantly 42) src/http-kit-opts))
            ok-cfg+bad (merge ok-cfg {:not-in/http-kit-opts :bad/value})]
        (when-mocking
          (run-server :fake/all-routes opts) => (do (assertions opts => ok-cfg) :ok)
          (assertions
            (-> (make-test-system ok-cfg+bad) .start :web-server :server) => :ok))))))
