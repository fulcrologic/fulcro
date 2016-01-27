(ns migrations.protocol-support-20160125
  (:require [untangled.datomic.impl.schema :as s]
            [untangled.datomic.impl.migration :as m]
            [datomic.api :as d]))

(defn transactions []
  [(s/generate-schema
      [(s/schema old-one
                 (s/fields
                   [name :string]
                   [madness :double]
                   [followers :ref :many]
                   ))
       (s/schema follower
                 (s/fields
                   [name :string]
                   [devotion :double]
                   ))
       ])])
