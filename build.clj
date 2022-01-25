(ns build
  "Fulcro's build script
    
  clojure -A:build -T:build jar
    
  For more information, run:

  clojure -A:deps -T:build help/doc"
  (:require [org.corfield.build :as bb]))

(def lib 'com.fulcrologic/fulcro)
(def version "3.5.12")

(defn jar "Build the JAR." [opts]
  (-> opts
      (bb/clean)
      (assoc :lib lib :version version 
        :src-dirs ["src/main"]
        :resource-dirs [])
      (bb/jar)))