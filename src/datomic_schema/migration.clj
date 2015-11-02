(ns datomic-schema.migration
  (:require [clojure.string :as s]
            [datomic.api :as d]
            [io.rkn.conformity :as c]
            [util.namespace :as n]
            [taoensso.timbre :as t]
            [util.logging :as l]
            [clojure.tools.namespace.find :refer [find-namespaces]]
            [clojure.java.classpath :refer [classpath]]
            )
  )

;; need this for testing..fatal is a non-mockable macro
(defn contains-lists? [l] (every? sequential? l))
(def dump-schema)

(defn all-migrations [migration-namespace]
  "Obtain all of the migrations from a given base namespace string (e.g. 
  \"datahub.migrations\")"
  (let [
        migration-keyword #(keyword (s/replace (n/namespace-name %) #"^(.*)[.]([^.]+)$" "$1/$2"))
        is-migration? #(and (.startsWith (n/namespace-name %) migration-namespace)
                            (not (.endsWith (n/namespace-name %) ".template")))
        migration-spaces (filter is-migration? (n/load-namespaces migration-namespace))
        transactions (fn [nspace] (if-let [tfunc (ns-resolve nspace 'transactions)]
                                    (tfunc)
                                    (do 
                                      (l/fatal "Missing 'transactions' function in " (n/namespace-name nspace))
                                      nil
                                      )
                                    ) 
                       )
        entry (fn [spc] (if-let [txn (transactions spc)]
                          (if (contains-lists? txn)
                            (vector (migration-keyword spc) {:txes txn })
                            (do
                              (l/fatal "Transaction function failed to return a list of transactions!" spc)
                              []
                              )
                            )
                          []))
        dated-kw-compare (fn [a b]
                           (.compareTo 
                             (or (re-find #"\d{8,}" (name a)) (name a)) 
                             (or (re-find #"\d{8,}" (name b)) (name b)) 
                             ))
        ]
    (for [mig (into (sorted-map-by dated-kw-compare)
                    (->> migration-spaces
                         (map entry)
                         (filter seq) ; eliminate empty items
                         )
                    )]
      (into {} [mig])
      )
    )
  )

(defn migrate
  "
  # (migrate connection namespace)

  Run all migrations. 

  ## Parameters
  * `dbconnection` A connection to a datomic database
  * `nspace` The namespace name that contains the migrations.

  ## Examples

  (migrate conn \"datahub.migrations\")
  "
  [dbconnection nspace]
  (let [migrations (all-migrations nspace)]
    (t/info "Running migrations for" nspace)
    (doseq [migration migrations
            nm (keys migration)]
      (t/info "Conforming " nm)
      (t/debug migration)
      (try
        (c/ensure-conforms dbconnection migration)
        (catch Exception e (taoensso.timbre/fatal "migration failed" e))
        )
      (if (c/conforms-to? (d/db dbconnection) nm)
        (t/info "Verified that database conforms to migration" nm)
        (t/error "Database does NOT conform to migration" nm)
        )
      )
    (t/debug "Schema is now" (dump-schema (d/db dbconnection)))
    )
  )

(defn dump-schema 
  "Show the non-system attributes of the schema on the supplied datomic database."
  [db]
  (let [system-ns #{"db" "db.type" "db.install" "db.part" 
                    "db.lang" "fressian" "db.unique" "db.excise" "db.bootstrap"
                    "db.cardinality" "db.fn" "db.alter" "confirmity"}
        idents    (d/q '[:find [?ident ...]
                         :in $ ?system-ns
                         :where
                         [_ :db/ident ?ident]
                         [(namespace ?ident) ?ns]
                         [((comp not contains?) ?system-ns ?ns)]]
                       db system-ns)
        ]
     (map #(d/touch (d/entity db %)) idents)
  )
  )

(defn dump-entity 
  "Dump an entity definition for an entity in a database.

  Parameters:
  * `database` : A datomic database
  * `entity-name`: The (string) name of the entity of interest.

  Returns the attributes of the supplied datomic database that are qualified by the given entity name"
  [db entity]
  (let [
        idents    (d/q '[:find [?ident ...]
                         :in $ ?nm
                         :where
                         [_ :db/ident ?ident]
                         [(namespace ?ident) ?ns]
                         [(= ?nm ?ns)]]
                       db entity)
        ]
    (map #(d/touch (d/entity db %)) idents)
    )
  )

(defn entity-extensions
  "
  Returns the datoms necessary to create entity extensions for documetation and foreign attributes in the schema of
  the named entity.

  Parameters:
  * `name` : The entity name (as a keyword or string)
  * `doc` : The doc string to include for the entity.
  * `foreign-attributes` : A set (or list) of namespace-qualified attributes to consider legal foreign attributes on the
  named entity.

  "
  [name doc foreign-attributes]
  (let [name (keyword name)
        refs  (map (fn [v] [:db/ident v]) (set foreign-attributes))
        basic  {:db/id (d/tempid :db.part/user) :entity/name name :entity/doc doc}
        trx    (if (empty? refs) basic (conj basic {:entity/foreign-attribute refs}))
        ]
    (list trx)
    )
  )
