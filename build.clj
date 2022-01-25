(ns build
  "Fulcro's build script
    
  clojure -A:build -T:build jar
    
  For more information, run:

  clojure -A:deps -T:build help/doc"
  (:require 
    [clojure.edn :as edn]
    [org.corfield.build :as bb]))

(def src-dirs (:paths (edn/read-string (slurp "deps.edn"))))

(def lib 'com.fulcrologic/fulcro)
(def version "3.5.12")

(defn jar "Build the JAR." [opts]
  (-> opts
      (bb/clean)
      (assoc :lib lib :version version 
        :src-dirs src-dirs
        :resource-dirs [])
      (bb/jar)))