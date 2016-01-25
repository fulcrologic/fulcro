(ns untangled.server.protocol-support-spec
  (:require
    [datomic.api :as d]
    [untangled.server.protocol-support :as ps]
    [untangled-spec.core :refer
     [specification behavior provided component assertions]]
    [untangled.util.fixtures :as fixture]
    [untangled.database :as udb]
    [survey.localized-strings :as ls]
    [survey.seeding :as seed]
    [survey.survey-data :as sd]
    [survey.survey-initial-fetch-protocol :as sifp]
    ))

(def protocol-support-data
  {:migrations "survey.migrations"
   :seed-data  [(seed/make-survey :datomic.id/survey0 "Survey Zero")
                (seed/make-range-question :datomic.id/question1 "Foo" 1 42)]
   :server-tx  '[(survey/add-question {:survey-id :datomic.id/survey0
                                       :template-id :datomic.id/question1
                                       :instance-id :om.tempid/inst-id0
                                       :rank 1.618})
                 {:surveys [:artifact/display-title]}]
   :response   {'survey/add-question {},
                :surveys
                [{:artifact/display-title
                  [{:db/id :datomic.id/survey0-label
                    :language/locale :language.locale/en,
                    :localized-string/value "Survey Zero"}]}]}})

(specification "test server response"
  (component "helper functions"
    (assertions
      "set-namespace :datomic.id/* -> :tempid/*"
      (ps/set-namespace :datomic.id/asdf "tempid") => :tempid/asdf

      "datomic-id->tempid"
      (ps/datomic-id->tempid [{:id :datomic.id/asdf :foo :om.id/asdf} {:datomic.id/asdf :id}])
      => [{:id :tempid/asdf :foo :om.id/asdf} {:tempid/asdf :id}]

      "collect-om-tempids"
      (ps/collect-om-tempids [{:id :om.tempid/qwack :foo :om.tempid/asdf} {:datomic.id/asdf :id}])
      => #{:om.tempid/qwack :om.tempid/asdf}

      "map-keys"
      (ps/map-keys inc (zipmap (range 3) (range 3))) => {1 0, 2 1, 3 2}

      "extract-tempids"
      (ps/extract-tempids {'survey/add-question {:tempids {:om.tempid/inst-id0 17592186045460}},
                           :surveys
                           [{:artifact/display-title
                             "Survey Zero"}]})
      => [{'survey/add-question {},
           :surveys
           [{:artifact/display-title
             "Survey Zero"}]}
          #{:om.tempid/inst-id0}]))

  (behavior "test server response w/ protocol data"
    (ps/test-server-response protocol-support-data))

  (behavior "test server response w/ `raw-seed-data` protocol data"
    (ps/test-server-response sd/raw-seed-data-protocol-support-data)))

(specification "rewrite-tempids"
  (behavior "rewrites tempids according to the supplied map"
    (assertions
      (ps/rewrite-tempids {:k :tempid/a} {:tempid/a 42}) => {:k 42}
      (ps/rewrite-tempids {:k {:db/id :tempid/a}} {:tempid/a 42}) => {:k {:db/id 42}}
      (ps/rewrite-tempids {:k [{:db/id :tempid/a}]} {:tempid/a 42}) => {:k [{:db/id 42}]}))
  (behavior "ignores keywords that are not tempids in mapping"
    (assertions
      (ps/rewrite-tempids {:k :a} {:a 42}) => {:k :a}))
  (behavior "leaves tempids in place if map entry is missing"
    (assertions
      (ps/rewrite-tempids {:k :tempid/a} {}) => {:k :tempid/a}
      (ps/rewrite-tempids {:k [{:db/id :tempid/a}]} {}) => {:k [{:db/id :tempid/a}]})))

;;TODO: move to protocol_support.cljs
(specification "check-delta"
  (behavior "Verifies data at arbitrary key-paths"
    (ps/check-delta {:k {:k2 1}} {[:k :k2] 1})
    (ps/check-delta {:k {:k2 #{1 2 3}}} {[:k :k2] #{2 3 1}}))
  (behavior "Verifies the lack of data at arbitrary key paths"
    (ps/check-delta {:k {:k2 1}} {[:k :k3] nil})))

;; TODO: not necessary?
(specification "vectors-to-sets"
  (behavior "converts nested vectors to sets"
    (assertions
      (ps/vectors-to-sets {:k {:k2 [{:a 1} {:b 2}]}}) => {:k {:k2 #{{:a 1} {:b 2}}}}
      (ps/vectors-to-sets {:k [{:k2 [{:a 1} {:b 2}]}]}) => {:k #{{:k2 #{{:a 1} {:b 2}}}}}))
  (behavior "ignores vectors of scalars"
    (assertions
      (ps/vectors-to-sets {:k {:k2 [1 2]}}) => {:k {:k2 [1 2]}})))
