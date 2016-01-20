(ns seeddata.devdata
  (:require 
    [seeddata.auth :as a]
    [untangled.server.database.seed :as s]
    [datomic.api :as d]
    )
  )

(defn sample [conn] 
  (let [entities (a/create-base-user-and-realm)
        linked-data (:items (s/link-entities entities))]
    @(d/transact conn linked-data)
    )
  )

(defn sample-oauth [conn]
  (let [entities (a/create-oauth-base-user-and-realm)
        linked-data (:items (s/link-entities entities))]
    @(d/transact conn linked-data)
    )
  )
