(ns untangled.util.seed
  (:require
    [datomic.api :as d]
    [taoensso.timbre :as timbre]
    )
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
(defn dbg [x] (println :DEBUG x) x)
(defn replace-id [entity idmap value]
  (cond
    (and (keyword? value) (get idmap value nil)) (get idmap value)
    (set? value) (into #{} (map (partial replace-id entity idmap) value))
    (vector? value) (into [] (map (partial replace-id entity idmap) value))
    :otherwise (do
                 (assert (not (is-tempid-keyword? value)) (str "Missing ID " value " for entity " entity (meta entity)))
                 value
                 )
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
