(ns migrations.protocol-support-20160125
  (:require [untangled.datomic.schema :as schema]
            [datomic.api :as d]))

(defn transactions []
  [(schema/generate-schema
      [(schema/schema old-one
                 (schema/fields
                   [name :string]
                   [madness :double]
                   [followers :ref :many]
                   ))
       (schema/schema follower
                 (schema/fields
                   [name :string]
                   [devotion :double]
                   ))
       ])])
