(ns untangled.server.impl.database.schema
  (:require
   [datomic.api :as d]
   [datomic.function :as df]))

;; The main schema functions
(defmacro fields
  "Simply a helper for converting (fields [name :string :indexed]) into {:fields {\"name\" [:string #{:indexed}]}}"
  [& fielddefs]
  (let [extract-type-and-options (fn [a [nm tp & opts]]
                                   (let [
                                         pure-opts (set (filter #(or (vector? %) (keyword? %) (string? %)) opts))
                                         custom-opts (first (filter #(map? %) opts))
                                         options (if (nil? custom-opts) [tp pure-opts] [tp pure-opts custom-opts])
                                         ]
                                     (assoc a (name nm) options)))
        defs (reduce extract-type-and-options {} fielddefs)]
    `{:fields ~defs}))

(defn schema*
  "Simply merges several maps into a single schema definition and add one or two helper properties"
  [name maps]
  (apply merge
         {:name name :basetype (keyword name) :namespace name}
         maps))

(defmacro schema
  [nm & maps]
  `(schema* ~(name nm) [~@maps]))

(defn part
  [nm]
  (keyword "db.part" nm))

;; The datomic schema conversion functions
(defn get-enums [basens part enums]
  (map (fn [n]
         (let [nm (if (string? n) (.replaceAll (.toLowerCase n) " " "-") (name n))]
           [:db/add (d/tempid part) :db/ident (keyword basens nm)])) enums))

(def unique-mapping
  {:db.unique/value :db.unique/value
   :db.unique/identity :db.unique/identity
   :unique-value :db.unique/value
   :unique-identity :db.unique/identity})

(defn field->datomic [basename part {:keys [gen-all? index-all?]} acc [fieldname [type opts custom]]]
  (let [uniq (first (remove nil? (map #(unique-mapping %) opts)))
        dbtype (keyword "db.type" (if (= type :enum) "ref" (name type)))
        result
        (cond->
          {:db.install/_attribute :db.part/db
           :db/id (d/tempid :db.part/db)
           :db/ident (keyword basename fieldname)
           :db/valueType dbtype
           :db/cardinality (if (opts :many) :db.cardinality/many :db.cardinality/one)}
          (or index-all? gen-all? (opts :indexed))
          (assoc :db/index (boolean (or index-all? (opts :indexed))))

          (or gen-all? (seq (filter string? opts)))
          (assoc :db/doc (or (first (filter string? opts)) ""))
          (or gen-all? (opts :fulltext))  (assoc :db/fulltext (boolean (opts :fulltext)))
          (or gen-all? (opts :component)) (assoc :db/isComponent (boolean (opts :component)))
          (or gen-all? (opts :nohistory)) (assoc :db/noHistory (boolean (opts :nohistory)))

          (:references custom)            (assoc :constraint/references (:references custom))
          (:with-values custom)           (assoc :constraint/with-values (:with-values custom))
          (opts :definitive)              (assoc :constraint/definitive (boolean (opts :definitive)))
          (opts :unpublished)             (assoc :constraint/unpublished (boolean (opts :unpublished)))
          )]
    (concat
     acc
     [(if uniq (assoc result :db/unique uniq) result)]
     (if (= type :enum) (get-enums (str basename "." fieldname) part (first (filter vector? opts)))))))

(defn schema->datomic [opts acc schema]
  (if (or (:db/id schema) (vector? schema))
    (conj acc schema) ;; This must be a raw schema definition
    (let [key (:namespace schema)
          part (or (:part schema) :db.part/user)]
      (reduce (partial field->datomic key part opts) acc (:fields schema)))))

(defn part->datomic [acc part]
  (conj acc
        {:db/id (d/tempid :db.part/db),
         :db/ident part
         :db.install/_partition :db.part/db}))

(defn generate-parts [partlist]
  (reduce (partial part->datomic) [] partlist))

(defn generate-schema
  ([schema] (generate-schema schema {:gen-all? true}))
   ([schema {:keys [gen-all? index-all?] :as opts}]
   (reduce (partial schema->datomic opts) [] schema)))

(defmacro with-require
  "A macro to be used with dbfn in order to add 'require'
  list to the function for using external libraries within
  database functions. For example:


       (with-require [datahub.validation [clojure.string :as s]]
         (dbfn ...))
  "
  [requires db-function]
  `(assoc-in ~db-function [:db/fn :requires] '~requires)
  )

(defmacro dbfn
  [name params partition & code]
  `{:db/id (datomic.api/tempid ~partition)
    :db/ident ~(keyword name)
    :db/fn (df/construct
            {:lang "clojure"
             :params '~params
             :code '~@code})})

(defmacro defdbfn
  "Define a datomic database function. All calls to datomic api's should be namespaced with datomic.api/ and you cannot use your own namespaces (since the function runs inside datomic)

  This defines a locally namespaced function as well - which is useful for testing.

  Your first parameter needs to always be 'db'.

  You'll need to commit the actual function's meta into your datomic instance by calling (d/transact (meta myfn))"
  [name params partition & code]
  `(def ~name
     (with-meta
       (fn ~name [~@params]
         ~@code)
       {:tx (dbfn ~name ~params ~partition ~@code)})))

(defn dbfns->datomic [& dbfn]
  (map (comp :tx meta) dbfn))
