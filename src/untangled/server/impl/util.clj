(ns untangled.server.impl.util
  (:require
    om.tempid
    [untangled.database :as udb]
    [clojure.tools.namespace.find :refer [find-namespaces]]
    [clojure.java.classpath :refer [classpath]])
  (:import (om.tempid TempId)))

(defn namespace-name [n]
  (str n))

(defn load-namespaces
  "Load all of the namespaces that contain the given name.
  This is useful for libraries that need to scan and load a
  set of namespaces, such as database migrations.

  Parameters:
  * `nspace-prefix` - The that the namespaces must start with.

  Returns a list of namespaces (as symbols) that were loaded."
  [nspace-prefix]
  (let [qualified-prefix (str nspace-prefix ".")
        qualified?       #(->
                           (namespace-name %)
                           (.startsWith qualified-prefix))
        namespaces       (->>
                           (classpath)
                           (find-namespaces)
                           (filter qualified?))]
    (doseq [n namespaces]
      (require n :reload))
    namespaces))

(defn deep-merge [& xs]
  (if (every? map? xs)
    (apply merge-with deep-merge xs)
    (last xs)))

(defn is-om-tempid? [val]
  (instance? TempId val))

(defn db-fixture-defs [fixture parser]
  "Given a db-fixture and an om parser, returns a map keyed by:
    `connection`: a connection to the fixture's db
    `parse`: a partially applied call to parser with an environment containing the connection (call it with query to parse)
    `seeded-tempid-map`: return value of seed-link-and-load-data
    `get-id`: give it a temp-id from seeded data, return the real id from `seeded-tempid-map`"

  (let [connection (udb/get-connection fixture)
        parse (partial parser {:connection connection})
        tempid-map (:seed-result (udb/get-info fixture))
        get-id (partial get tempid-map)]
    {:connection        connection
     :parse             parse
     :seeded-tempid-map tempid-map
     :get-id            get-id}))
