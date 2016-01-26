(ns migrations.protocol-support-20160125
  (:require [untangled.server.impl.database.schema :as s]
            [untangled.server.impl.database.migration :as m]
            [datomic.api :as d]))

(defn transactions []
  [(s/generate-schema
      [(s/schema old-one
                 (s/fields
                   [name :string]
                   [madness :double]
                   [cultists :ref :many]
                   ))
       (s/schema cultist
                 (s/fields
                   [name :string]
                   [devotion :double]
                   ))
       ])])
