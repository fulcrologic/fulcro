(ns untangled.datomic-schema.fetch
  (:require
    clojure.set
    [datomic.api :as d]
    [untangled.datomic-schema.migration :as m]
    [clojure.string :as str]
    ))

(defn- get-entity-from-attribute [key]
  (keyword (subs (first (str/split (str key) #"/")) 1))
  )

(defn- map-schema-results [acc attribute]
  (let [entities (first acc)
        definitives (second acc)
        id (:db/ident attribute)
        entityid (get-entity-from-attribute id)
        values (cond-> {:db/valueType   (:db/valueType attribute)
                        :db/cardinality (:db/cardinality attribute)
                        }
                       (:db/doc attribute) (assoc :db/doc (:db/doc attribute))
                       (:db/entity-doc attribute) (assoc :db/entity-doc (:rest/entity-doc attribute))
                       (:constraint/definitive attribute) (assoc :constraint/definitive (:constraint/definitive attribute))
                       (:constraint/unpublished attribute) (assoc :constraint/unpublished (:constraint/unpublished attribute))
                       (:constraint/references attribute) (assoc :constraint/references (:constraint/references attribute))
                       (:db/fulltext attribute) (assoc :db/fulltext (:db/fulltext attribute))
                       (:db/index attribute) (assoc :db/index (:db/index attribute))
                       (:db/unique attribute) (assoc :db/unique (:db/unique attribute))
                       (:db/isComponent attribute) (assoc :db/isComponent (:db/isComponent attribute))
                       )
        currvalues (if (contains? entities entityid) (:attributes (entityid entities)) {})
        newvalues (merge {id values} currvalues)
        entities-update (assoc entities entityid {:attributes newvalues})
        definitives-update (if (:constraint/definitive attribute) (conj definitives id) definitives)]
    [entities-update definitives-update]
    )
  )

; given a datomic-database and options build the schema for each type of entities described in the options
(defn- build-entity-representations [db]
  (let [partition (d/q '[:find ?e . :where [?e :db/ident :db.part/db]] db)
        schema (m/dump-schema db)
        attributes (get (group-by #(d/part (:db/id %)) schema) partition)
        map-results (reduce map-schema-results [{} []] attributes)]
    map-results
    )
  )

(defn- resolve-foreign-key [keys all-entity-values]
  (let [entity (first keys)
        attribute (second keys)]
    {attribute (-> all-entity-values entity :attributes attribute)}
    ))

; add the foreign attributes to the attribute map
(defn- add-foreign-atrributes [entity-key all-entity-values foreign-attributes]
  (let [entity-values (entity-key all-entity-values)]
    (if (> (count foreign-attributes) 0)
      (let [attributes (:attributes entity-values)
            foreign-attributes-expanded (map (fn [key] [(get-entity-from-attribute key) key]) foreign-attributes)
            extended-attributes (into {} (map #(resolve-foreign-key % all-entity-values) foreign-attributes-expanded))
            combined-attributes (into {} [attributes extended-attributes])]
        (assoc-in entity-values [:attributes] combined-attributes)
        )
      entity-values
      )
    )
  )

(defn- append-foreign-attributes [db entities]
  (let [attrs (d/q '[:find ?name ?attr :where [?e :entity/name ?name] [?e :entity/foreign-attribute ?f] [?f :db/ident ?attr]] db)
        foreign-attributes (reduce (fn [acc a]
                                     (let [k (first a)
                                           c (if (nil? (k acc)) [] (k acc))
                                           n (conj c (second a))]
                                       (assoc acc k n))) {} attrs)
        ]
    (reduce (fn [acc entity-key]
              (assoc acc entity-key (add-foreign-atrributes entity-key entities (entity-key foreign-attributes)))) {} (keys entities))
    )
  )

(defn- add-entity-extensions [db entities]
  (let [docs (into {} (d/q '[:find ?name ?doc :where [?e :entity/doc ?doc] [?e :entity/name ?name]] db))
        entities-with-docs (into {}
                                 (map (fn [e]
                                        (let [doc (if (nil? ((first e) docs)) "" ((first e) docs))]
                                          {(first e) (assoc (second e) :doc doc)})) entities))
        entities-with-foreign-keys (append-foreign-attributes db entities-with-docs)]
    entities-with-foreign-keys
    )
  )

(defn fetch-schema [datomic-connection]
  "Retrieves the schema information from the database and constructs a map that organizes that schema informtion by
   entity type.

  Parameters:
  datomic-connection - An open connection to a datomic database

  Output: { :entities {:realm {:atttributes {:realm/name {:db/valueType    :db.type/string
                                                 :db/isComponent  false
                                                 :db/fulltext     false
                                                 :db/doc        \"Realm Name\"
                                                 :constraint/definitive true}}
                                             {:actor/system {:db/valueType    :db.type/string
                                                           :db/isComponent  false
                                                           :db/fulltext     false
                                                           :db/doc        \"Actor System\"
                                                           :constraint/definitive true}}
                                             {:actor/uuid {:db/valueType    :db.type/uuid
                                                           :db/isComponent  false
                                                           :db/fulltext     false
                                                           :db/doc        \"Actor System\"
                                                           :constraint/definitive true}}
                                          :doc \"entity-doc\" }}}"
  (let [datomic-database (d/db datomic-connection)
        map-results (build-entity-representations datomic-database)
        entites (apply dissoc (first map-results) [:constraint])
        definitive (second map-results)
        entities-with-attached-extensions (add-entity-extensions datomic-database entites)]
    {:entities entities-with-attached-extensions :definitive definitive}
    )
  )
