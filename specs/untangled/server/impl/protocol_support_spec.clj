(ns untangled.server.impl.protocol-support-spec
  (:require [untangled-spec.core :refer [specification assertions behavior]]
            [untangled.server.impl.protocol-support :as ips]))

(specification "helper functions"
  (assertions
    "set-namespace :datomic.id/* -> :tempid/*"
    (ips/set-namespace :datomic.id/asdf "tempid") => :tempid/asdf

    "collect-om-tempids"
    (ips/collect-om-tempids [{:id :om.tempid/qwack :foo :om.tempid/asdf} {:datomic.id/asdf :id}])
    => #{:om.tempid/qwack :om.tempid/asdf}

    "map-keys"
    (ips/map-keys inc (zipmap (range 3) (range 3))) => {1 0, 2 1, 3 2}

    "extract-tempids"
    (ips/extract-tempids {'survey/add-question {:tempids {:om.tempid/inst-id0 17592186045460}},
                          :surveys
                          [{:artifact/display-title
                            "Survey Zero"}]})
    => [{'survey/add-question {},
         :surveys
         [{:artifact/display-title
           "Survey Zero"}]}
        {:om.tempid/inst-id0 17592186045460}]))

(specification "rewrite-tempids"
  (behavior "rewrites tempids according to the supplied map"
    (assertions
      (ips/rewrite-tempids {:k :tempid/a} {:tempid/a 42}) => {:k 42}
      (ips/rewrite-tempids {:k {:db/id :tempid/a}} {:tempid/a 42}) => {:k {:db/id 42}}
      (ips/rewrite-tempids {:k [{:db/id :tempid/a}]} {:tempid/a 42}) => {:k [{:db/id 42}]}))
  (behavior "ignores keywords that are not tempids in mapping"
    (assertions
      (ips/rewrite-tempids {:k :a} {:a 42}) => {:k :a}))
  (behavior "leaves tempids in place if map entry is missing"
    (assertions
      (ips/rewrite-tempids {:k :tempid/a} {}) => {:k :tempid/a}
      (ips/rewrite-tempids {:k [{:db/id :tempid/a}]} {}) => {:k [{:db/id :tempid/a}]})))
