(ns untangled.util.seed-spec
  (:require
    [datomic.api :as d]
    [untangled.util.seed :as s]
    [taoensso.timbre :as timbre]
    )
  (:use midje.sweet)
  )

(facts "Temporary ID"
       (fact "tempid keywords are any keywords in namespaces starting with tempid"
             (s/is-tempid-keyword? :tempid/blah) => true
             (s/is-tempid-keyword? :tempid.other/blah) => true
             (s/is-tempid-keyword? :tempid.a.b.c/dude) => true
             (s/is-tempid-keyword? :other.tempid/blah) => false
             )
       )

(facts "Assigning a temp ID"
       (fact "Creates a new tempid for a list datom"
             (s/assign-temp-id { :tempid/a 1 } [ :db/add :tempid/b :a/boo "hello" ]) => { :tempid/a 1  
                                                                                       :tempid/b ..id..}
             (provided
               (d/tempid :db.part/user) => ..id..
               )
             )
       (fact "Tolerates an existing tempid for a list datom"
             (s/assign-temp-id { :tempid/a 1 } [ :db/add :tempid/a :a/boo "hello" ]) => { :tempid/a 1 }
             )
       (fact "Refuses to assign an id if the same ID is already in the id map"
             (s/assign-temp-id { :tempid/a 1 } { :db/id :tempid/a :a/boo "hello" }) => (throws AssertionError #"Entity uses a duplicate ID")
             )
       (fact "Includes the entity's metadata in the duplicate ID message"
             (s/assign-temp-id { :tempid/a 1 } ^{ :line 4 } { :db/id :tempid/a :a/boo "hello" }) => (throws AssertionError #"duplicate ID.*line 4")
             )
       (fact "returns the original map if the item has no ID field"
             (s/assign-temp-id ..old-ids.. { :a/boo "hello" }) => ..old-ids..
             )
       (fact "Generates an ID and puts it in the ID map when a tempid field is present"
             (s/assign-temp-id {} { :db/id :tempid/thing :a/boo "hello" }) => { :tempid/thing ..id.. }
             (provided
               (d/tempid :db.part/user) => ..id..
               )
             )
       (fact "recognizes tempid namespaces that have sub-namespaces like tempid.boo"
             (s/assign-temp-id {} { :db/id :tempid.boo/thing :a/boo "hello" }) => { :tempid.boo/thing ..id.. }
             (provided
               (d/tempid :db.part/user) => ..id..
               )
             )
       )

(facts "replacing ids"
       (fact "replaces a tempid keyword value with the actual tempid"
             (s/replace-id {} { :tempid/thing 22 } :tempid/thing) => 22
             )
       (fact "replaces a vector containing tempid keyword values with a vector that has the actual tempids"
             (s/replace-id {} { :tempid/thing 22 :tempid/other 42 } [ :tempid/thing :tempid/other ]) => (fn [v] (and (vector? v) (= v [22 42])))
             )
       (fact "replaces a set containing tempid keyword values with a set that has the actual tempids"
             (s/replace-id {} { :tempid/thing 22 :tempid/other 42 } #{ :tempid/thing :tempid/other }) => #{22 42}
             )
       (fact "does not replace scalar values that are not keyed in the map"
             (s/replace-id {} { :tempid/thing 22 :tempid/other 42 } :boo) => :boo
             (s/replace-id {} { :tempid/thing 22 :tempid/other 42 } "hello") => "hello"
             (s/replace-id {} { :tempid/thing 22 :tempid/other 42 } 43) => 43
             (s/replace-id {} { :tempid/thing 22 :tempid/other 42 } 4/3) => 4/3
             (s/replace-id {} { :tempid/thing 22 :tempid/other 42 } [1 2 3]) => [1 2 3]
             (s/replace-id {} { :tempid/thing 22 :tempid/other 42 } #{5 4 3}) => #{3 4 5}
             (s/replace-id {} { :tempid/thing 22 :tempid/other 42 } {:k1 5 :k2 3}) => {:k1 5 :k2 3}
             )
       )

(facts "Assigning ids in an entity"
       (fact "throws an AssertionError if a tempid keyword is referred to that is not in the ID map"
             (s/assign-ids { :tempid/thing 22 :tempid/other 42 } 
                         ^{:line 33} { :other/thing :tempid/blah :user/name "Tony" }) => (throws AssertionError #"Missing.*tempid/blah.*line 33")
             )
       (fact "replaces tempids with values from the idmap in scalar values"
             (s/assign-ids { :tempid/thing 22 :tempid/other 42 } 
                         { :other/thing :tempid/thing :user/name "Tony" }) => { :other/thing 22 :user/name "Tony"}
             )
       (fact "replaces tempids with values from the idmap in vector values"
             (s/assign-ids { :tempid/thing 22 :tempid/other 42 } { :other/thing [ :tempid/thing :tempid/other ] :user/name "Tony" }) =>
             { :other/thing [ 22 42 ] :user/name "Tony"}
             )
       (fact "replaces tempids with values from the idmap in set values"
             (s/assign-ids { :tempid/thing 22 :tempid/other 42 } { :other/thing #{ :tempid/thing :tempid/other } :user/name "Tony" }) =>
             { :other/thing #{ 22 42 } :user/name "Tony"}
             )
       (fact "replaces temporary ID of datomic list datom with calculated tempid"
             (s/assign-ids { :tempid/thing 1 } [:db/add :tempid/thing :user/name "tony"]) =>
             [:db/add 1 :user/name "tony"]
             )
       (fact "replaces temporary IDs in lists within datomic list datom"
             (s/assign-ids { :tempid/thing 1 :tempid/that 2} [:db/add ..id.. :user/parent [:tempid/that] ]) =>
             [:db/add ..id.. :user/parent [2]]
             )
       (fact "throws an AssertionError if the idmap does not contain the id"
             (s/assign-ids { } [:db/add :tempid/this :user/parent [:tempid/that] ]) =>
             (throws AssertionError #"Missing ID :tempid/this")
             )
       )

(facts "linking entities"
       (fact "does not accept a map as an argument" 
             (s/link-entities { :k :v }) => (throws AssertionError #"Argument must be a")
             )
       )


