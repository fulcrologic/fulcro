(ns untangled.server.impl.protocol-support-spec
  (:require [untangled-spec.core :refer [specification assertions behavior]]
            [untangled.server.impl.protocol-support :as ips]
            [om.tempid :as omt]
            [clojure.test :as t]
            [taoensso.timbre :as timbre]))

(specification "helper functions"
  (assertions
    "collect-om-tempids"
    (ips/collect-om-tempids [{:id :om.tempid/qwack :foo :om.tempid/asdf} {:datomic.id/asdf :id}])
    => #{:om.tempid/qwack :om.tempid/asdf}

    "extract-tempids"
    (ips/extract-tempids {'survey/add-question {:tempids {:om.tempid/inst-id0 17592186045460}},
                          :surveys
                          [{:artifact/display-title
                            "Survey Zero"}]})
    => [{'survey/add-question {},
         :surveys
         [{:artifact/display-title
           "Survey Zero"}]}
        {:om.tempid/inst-id0 17592186045460}]

    "nested-sort sorts collections recursively"
    (ips/recursive-sort-by hash {'foo {:bar [{:db/id 3} {:db/id 1 :foo [1 0 4 2 3]}]}})
    => {'foo {:bar [{:db/id 1 :foo [3 2 4 0 1]} {:db/id 3}]}})

  (let [[with-om-tempids omt->fake-omt] (ips/rewrite-om-tempids [:om.tempid/asdf :datomic.id/qwer :foo/bar])]
    (assertions "rewrite-om-tempids"
      (-> omt->fake-omt vals set) => #{:om.tempid/asdf}
      (first with-om-tempids) =fn=> omt/tempid?
      (drop 1 with-om-tempids) => [:datomic.id/qwer :foo/bar])))

(specification "rewrite-tempids"
  (behavior "rewrites tempids according to the supplied map"
    (assertions
      (ips/rewrite-tempids {:k :datomic.id/a} {:datomic.id/a 42}) => {:k 42}
      (ips/rewrite-tempids {:k {:db/id :datomic.id/a}} {:datomic.id/a 42}) => {:k {:db/id 42}}
      (ips/rewrite-tempids {:k [{:db/id :datomic.id/a}]} {:datomic.id/a 42}) => {:k [{:db/id 42}]}))
  (behavior "ignores keywords that are not tempids in mapping"
    (assertions
      (ips/rewrite-tempids {:k :a} {:a 42}) => {:k :a}))
  (behavior "leaves tempids in place if map entry is missing"
    (assertions
      (ips/rewrite-tempids {:k :datomic.id/a} {}) => {:k :datomic.id/a}
      (ips/rewrite-tempids {:k [{:db/id :datomic.id/a}]} {}) => {:k [{:db/id :datomic.id/a}]})))
