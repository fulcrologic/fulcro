(ns ^:figwheel-always fulcro.test-main
  (:require
    [fulcro-spec.selectors :as sel]
    [fulcro-spec.suite :refer [def-test-suite]]))

(def-test-suite client-tests {:ns-regex #"fulcro.*-spec"}
  {:default   #{::sel/none :focused}
   :available #{:focused}})

(defn start [] (client-tests))
(defn stop [done] (done))
(defn ^:export init [] (start))
