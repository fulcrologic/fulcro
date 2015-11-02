(ns datahub.namespace
  (:require [clojure.string :as s]
            [datomic.api :as d]
            [io.rkn.conformity :as c]
            [taoensso.timbre :refer [debug info fatal error]]
            [clojure.tools.namespace.find :refer [find-namespaces]]
            [clojure.java.classpath :refer [classpath]]
            )
  (:use midje.sweet)
  )

(defn namespace-name [n] (str n))

(defn load-namespaces
  "Load all of the namespaces that contain the given name. This is useful for libraries that need to scan and load a
  set of namespaces, such as database migrations.

  Parameters:
  * `nspace-prefix` - The that the namespaces must start with.

  Returns a list of namespaces (as symbols) that were loaded."
  [nspace-prefix]
  (let [qualified-prefix (str nspace-prefix ".")
        namespaces (->> (classpath)
                        (find-namespaces)
                        (filter #(-> (namespace-name %) (.startsWith qualified-prefix))))]
    (doseq [n namespaces] (require n :reload))
    namespaces
    ))

(facts :integration "load-namespaces"
       (fact :integration "processes namespaces that start with a given prefix"
             (every? #(.startsWith (str %) "resources.load-namespace") (load-namespaces "resources.load-namespaces")) => true
             )
       (fact :integration "returns the symbols of namespaces that were loaded"
             (load-namespaces "resources.load-namespace") => ['resources.load-namespace.load-namespaces]
             )
       (fact :integration "will not accept a partial package name as a prefix"
             (load-namespaces "resources.load-namespac") => []
             )
       )

