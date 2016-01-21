(ns untangled.server.impl.util
  (:require
    [clojure.tools.namespace.find :refer [find-namespaces]]
    [clojure.java.classpath :refer [classpath]]))

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
