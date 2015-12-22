(ns untangled.datomic-schema.migration-spec
  (:require [taoensso.timbre :refer [fatal]]
            [untangled.util.namespace :as n]
            [untangled.datomic-schema.migration :as m]
            [untangled.util.logging :as logger]
            [untangled-spec.core :refer [specification
                                         assertions
                                         when-mocking
                                         component
                                         behavior]]
            [clojure.test :refer :all]))

(specification "all-migrations"
  (behavior "INTEGRATION - finds migrations that are in the correct package and ignores the template"
    (assertions
      (m/all-migrations "util.migration-fixtures")
      => '(
            {:util.migration-fixtures/A {:txes [[{:item 1}]]}}
            {:util.migration-fixtures/B {:txes [[{:item 2}]]}})))

  (behavior "does not find migrations that are in other packages"
    (let [mig1 "some.migration1"
          mig2 "some.migration2"]
      (when-mocking
        (n/load-namespaces "my.crap") => ['mig1 'mig2]

        (assertions
          (m/all-migrations "my.crap") => '()))))

  (behavior "skips generation and complains if the 'transactions' function is missing."
    ;; Punting here.  Won't work because load-namespaces gathers ns's from disk.
    (do
      (remove-ns 'my.crap.A)
      (create-ns 'my.crap.A))
    (when-mocking
      ;; TODO:  Not really sure here.
      ;(n/namespace-name :..migration1..) => "my.crap.A"
      ;(n/load-namespaces "my.crap") => ['my.crap.A]
      ;(ns-resolve 'mig1 'transactions) => nil
      (logger/fatal #"Missing" #"my\.crap\.A") => :ignored

      (assertions
        (m/all-migrations "my.crap") => '())))

  (behavior "skips the migration and reports an error if the 'transactions' function fails to return a list of lists"
    (when-mocking
      ;; TODO:  Dunno.
      ;(n/namespace-name :..migration1..) => "my.crap.A"
      ;(n/load-namespaces "my.crap") => [:..migration1..]
      ;(ns-resolve :..migration1.. 'transactions) => (fn [] [{}])
      ;(logger/fatal #"Transaction function failed to return a list of transactions!" :..migration1..) => :ignored
      (assertions
        (m/all-migrations "my.crap") => '())))

  (behavior "skips the migration named 'template'"
    (when-mocking
      (n/namespace-name :..migration1..) => "my.crap.template"
      (n/load-namespaces "my.crap") => [:..migration1..]

      (assertions
        (m/all-migrations "my.crap") => '()))))
