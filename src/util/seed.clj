(ns util.seed
  (:require
    [datomic.api :as d]
    [taoensso.timbre :as timbre]
    )
  (:use midje.sweet)
  )

(defmacro generate-entity
  "Generate a ready-to-link datomic object. The object data can take the form

       {
         :db/id :tempid/myid
         :attr  value
         :refattr  tempid | realid
       }

  Any scalar values will be used literally. Any database ID can be either a real
  datomic entity ID, or a :tempid/name placeholder. Using the same :tempid/name
  placeholder in multiple entities within a transaction will result in the exact
  same tempid when joined into a real transaction.

  As with normal entity maps in datomic, you may put values for many fields as
  a set, including tempids:

       { :db/id :tempid/myid
         :attr  value
         :refattrs #{ tempid realid realid tempid }
       }

  The primary purpose of this macro is to validate and add metadata to the entity.
  "
  [obj]
  (let [f *file*]
    (with-meta obj (assoc (meta &form) :file f))
    )
  )

(defn is-tempid-keyword? [v] (and (keyword? v) (some-> v namespace (.startsWith "tempid"))))

(facts "Temporary ID"
       (fact "tempid keywords are any keywords in namespaces starting with tempid"
             (is-tempid-keyword? :tempid/blah) => true
             (is-tempid-keyword? :tempid.other/blah) => true
             (is-tempid-keyword? :tempid.a.b.c/dude) => true
             (is-tempid-keyword? :other.tempid/blah) => false
             )
       )

(defn assign-temp-id 
  "Scans item (which may be a map of attributes or a datomic :db/add list) for
  an ID field with a keyword namespaced within tempid (e.g.
  :tempid.boo/myid) as a value. If found, it requests a new temporary ID from
  datomic and remembers the association in the returned map.

  If the item is a map and the id-map already contains a mapping for the item,
  then this function will throw an AssertionError that includes the metadata of
  the item in question, which will include the file/line if the entity was
  created via the generate-entity macro.

  If the item is a datomic datom list, then no duplicate checking is applied, and
  new ids will be added to the map, while repeat entries will be ignored.
  "
  [id-map item]
  (cond
    (map? item)  (if-let [id (keyword (:db/id item))]
                  (if (.startsWith (namespace id) "tempid")
                    (do
                      (assert (not (contains? id-map id)) (str "Entity uses a duplicate ID: " (meta item)))
                      (assoc id-map id (d/tempid :db.part/user))
                      )
                    id-map)
                  id-map
                  )
    (sequential? item) (let [id (nth item 1)]
                         (if (and (.startsWith (namespace id) "tempid") (not (contains? id-map id)))
                           (assoc id-map id (d/tempid :db.part/user))
                           id-map)
                         )
    :otherwise (assert false "Invalid entry in data to link. Must be list or map")
    )
  )

(facts "Assigning a temp ID"
       (fact "Creates a new tempid for a list datom"
             (assign-temp-id { :tempid/a 1 } [ :db/add :tempid/b :a/boo "hello" ]) => { :tempid/a 1  
                                                                                       :tempid/b ..id..}
             (provided
               (d/tempid :db.part/user) => ..id..
               )
             )
       (fact "Tolerates an existing tempid for a list datom"
             (assign-temp-id { :tempid/a 1 } [ :db/add :tempid/a :a/boo "hello" ]) => { :tempid/a 1 }
             )
       (fact "Refuses to assign an id if the same ID is already in the id map"
             (assign-temp-id { :tempid/a 1 } { :db/id :tempid/a :a/boo "hello" }) => (throws AssertionError #"Entity uses a duplicate ID")
             )
       (fact "Includes the entity's metadata in the duplicate ID message"
             (assign-temp-id { :tempid/a 1 } ^{ :line 4 } { :db/id :tempid/a :a/boo "hello" }) => (throws AssertionError #"duplicate ID.*line 4")
             )
       (fact "returns the original map if the item has no ID field"
             (assign-temp-id ..old-ids.. { :a/boo "hello" }) => ..old-ids..
             )
       (fact "Generates an ID and puts it in the ID map when a tempid field is present"
             (assign-temp-id {} { :db/id :tempid/thing :a/boo "hello" }) => { :tempid/thing ..id.. }
             (provided
               (d/tempid :db.part/user) => ..id..
               )
             )
       (fact "recognizes tempid namespaces that have sub-namespaces like tempid.boo"
             (assign-temp-id {} { :db/id :tempid.boo/thing :a/boo "hello" }) => { :tempid.boo/thing ..id.. }
             (provided
               (d/tempid :db.part/user) => ..id..
               )
             )
       )

(defn- replace-id [entity idmap value]
  (cond 
    (and (keyword? value) (value idmap)) (value idmap)
    (set? value) (into #{} (map (partial replace-id entity idmap) value))
    (vector? value) (into [] (map (partial replace-id entity idmap) value))
    :otherwise (do
                 (assert (not (is-tempid-keyword? value)) (str "Missing ID " value " for entity " entity (meta entity)))
                 value
                 )
    )
  )

(facts "replacing ids"
       (fact "replaces a tempid keyword value with the actual tempid"
             (replace-id {} { :tempid/thing 22 } :tempid/thing) => 22
             )
       (fact "replaces a vector containing tempid keyword values with a vector that has the actual tempids"
             (replace-id {} { :tempid/thing 22 :tempid/other 42 } [ :tempid/thing :tempid/other ]) => (fn [v] (and (vector? v) (= v [22 42])))
             )
       (fact "replaces a set containing tempid keyword values with a set that has the actual tempids"
             (replace-id {} { :tempid/thing 22 :tempid/other 42 } #{ :tempid/thing :tempid/other }) => #{22 42}
             )
       (fact "does not replace scalar values that are not keyed in the map"
             (replace-id {} { :tempid/thing 22 :tempid/other 42 } :boo) => :boo
             (replace-id {} { :tempid/thing 22 :tempid/other 42 } "hello") => "hello"
             (replace-id {} { :tempid/thing 22 :tempid/other 42 } 43) => 43
             (replace-id {} { :tempid/thing 22 :tempid/other 42 } 4/3) => 4/3
             (replace-id {} { :tempid/thing 22 :tempid/other 42 } [1 2 3]) => [1 2 3]
             (replace-id {} { :tempid/thing 22 :tempid/other 42 } #{5 4 3}) => #{3 4 5}
             (replace-id {} { :tempid/thing 22 :tempid/other 42 } {:k1 5 :k2 3}) => {:k1 5 :k2 3}
             )
       )

(defn assign-ids 
  "Replaces any references to temporary IDs that exist in idmap with the actual
  tempid.

  The entity may be a map-form or a list-form (datomic datom).

  Returns an updated entity that has the correct temporary IDs.
  "
  [idmap entity] 
  (cond 
    (map? entity) (reduce (fn [e k] 
                            (let [existing-value (k e)
                                  new-value (replace-id entity idmap existing-value)]
                              (assoc e k new-value))
                            )
                          entity (keys entity))
    (sequential? entity) (map (partial replace-id entity idmap) entity)
    )
  )

(facts "Assigning ids in an entity"
       (fact "throws an AssertionError if a tempid keyword is referred to that is not in the ID map"
             (assign-ids { :tempid/thing 22 :tempid/other 42 } 
                         ^{:line 33} { :other/thing :tempid/blah :user/name "Tony" }) => (throws AssertionError #"Missing.*tempid/blah.*line 33")
             )
       (fact "replaces tempids with values from the idmap in scalar values"
             (assign-ids { :tempid/thing 22 :tempid/other 42 } 
                         { :other/thing :tempid/thing :user/name "Tony" }) => { :other/thing 22 :user/name "Tony"}
             )
       (fact "replaces tempids with values from the idmap in vector values"
             (assign-ids { :tempid/thing 22 :tempid/other 42 } { :other/thing [ :tempid/thing :tempid/other ] :user/name "Tony" }) =>
             { :other/thing [ 22 42 ] :user/name "Tony"}
             )
       (fact "replaces tempids with values from the idmap in set values"
             (assign-ids { :tempid/thing 22 :tempid/other 42 } { :other/thing #{ :tempid/thing :tempid/other } :user/name "Tony" }) =>
             { :other/thing #{ 22 42 } :user/name "Tony"}
             )
       (fact "replaces temporary ID of datomic list datom with calculated tempid"
             (assign-ids { :tempid/thing 1 } [:db/add :tempid/thing :user/name "tony"]) =>
             [:db/add 1 :user/name "tony"]
             )
       (fact "replaces temporary IDs in lists within datomic list datom"
             (assign-ids { :tempid/thing 1 :tempid/that 2} [:db/add ..id.. :user/parent [:tempid/that] ]) =>
             [:db/add ..id.. :user/parent [2]]
             )
       (fact "throws an AssertionError if the idmap does not contain the id"
             (assign-ids { } [:db/add :tempid/this :user/parent [:tempid/that] ]) =>
             (throws AssertionError #"Missing ID :tempid/this")
             )
       )

(defn link-entities
  "Walks the given entities (list, set, vector) and:

  * ensures the tempids have a proper datomic temporary ID
  * ensures that a given tempid is NOT used as the ID for more than one entity
  * ensures that all references to tempIDs end up using the real datomic temporary
  ID for that entity.

  Returns a list of entities with their IDs properly linked.
  "
  [e]
  (assert (not (map?  e)) "Argument must be a list, set, or vector")
  (let [tempids (reduce assign-temp-id {} (seq e))]
    { :items (for [entry e] (assign-ids tempids entry))
      :tempids tempids }
    )
  )

(facts "linking entities"
       (fact "does not accept a map as an argument" 
             (link-entities { :k :v }) => (throws AssertionError #"Argument must be a")
             )
       )

(defn link-and-load-seed-data 
  "Links the given data (by temp IDs), and loads it into the database. This
  function returns a map from user-generated :tempid/... IDs to the read IDs
  that were used by the database.
  
  The datoms may be in map or list form (a list of lists, or a list of maps),
  and you can use generate-entities on the maps for better debugging support.
  "
  [conn datoms]
  (let [link              (link-entities datoms)
        linked-data       (:items link)
        assigned-tempids  (:tempids link)
        result            @(d/transact conn linked-data)
        realid-map        (:tempids result)
        ]
      (reduce (fn [a k] (assoc a k (d/resolve-tempid (d/db conn) realid-map (k a))))  assigned-tempids (keys  assigned-tempids))
    )
  )
