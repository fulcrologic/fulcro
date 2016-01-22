(ns untangled.server.impl.util
  (:require
    om.tempid
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
        qualified? #(->
                     (namespace-name %)
                     (.startsWith qualified-prefix))
        namespaces (->>
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


