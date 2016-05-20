(ns untangled.server.impl.components.web-server-spec
  (:require [com.stuartsierra.component :as component]
            [untangled.server.impl.components.web-server :as src]
            [untangled.server.core :refer [make-web-server]]
            [untangled-spec.core :refer
             [specification component behavior assertions when-mocking provided]]))

(defn make-test-system []
  (component/system-map
    :config {:value {:port 1337}}
    :handler {:all-routes :fake/all-routes}
    :web-server (make-web-server)))

(specification "WebServer"
  (component "start"
    (behavior "correctly grabs the port & all-routes, and returns the started server under :server"
      (when-mocking
        (org.httpkit.server/run-server routes options) => :ok
        (assertions
          (-> (make-test-system) .start :web-server :server) => :ok)))))
