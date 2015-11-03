(ns untangled.datomic-schema.migration-spec
  (:require [taoensso.timbre :refer [fatal]]
            [untangled.util.namespace :as n]
            [untangled.util.logging :as l]
            [untangled.datomic-schema.migration :as m])
  (:use midje.sweet)
  )

(facts "all-migrations"
         (fact "INTEGRATION - finds migrations that are in the correct package and ignores the template"
               (m/all-migrations "util.migration-fixtures") => '(
                                                                      { :util.migration-fixtures/A {:txes [[{:item 1}]] } }
                                                                      { :util.migration-fixtures/B {:txes [[{:item 2}]] } }
                                                                      )
               )
       (fact "does not find migrations that are in other packages"
             (m/all-migrations "my.crap") => '()
             (provided
               (n/load-namespaces "my.crap") => [..migration1.. ..migration2..]
               (n/namespace-name ..migration1..) => "other.A"
               (n/namespace-name ..migration2..) => "thing.crap.B"
               )
             )

       (fact "skips generation and complains if the 'transactions' function is missing."
             (m/all-migrations "my.crap") => '()
             (provided
               (n/namespace-name ..migration1..) => "my.crap.A"
               (n/load-namespaces "my.crap") => [..migration1..]
               (ns-resolve ..migration1.. 'transactions) => nil
               (l/fatal #"Missing" #"my.crap.A") => :ignored
               )
             )

       (fact "skips the migration and reports an error if the 'transactions' function fails to return a list of lists"
             (m/all-migrations "my.crap") => '()
             (provided
               (n/namespace-name ..migration1..) => "my.crap.A"
               (n/load-namespaces "my.crap") => [..migration1..]
               (ns-resolve ..migration1.. 'transactions) => (fn [] [{}])
               (l/fatal #"Transaction function failed to return a list of transactions!" ..migration1..) => :ignored
               )
             )

       (fact "skips the migration named 'template'"
             (m/all-migrations "my.crap") => '()
             (provided
               (n/namespace-name ..migration1..) => "my.crap.template"
               (n/load-namespaces "my.crap") => [..migration1..]
               )
             )
       )
