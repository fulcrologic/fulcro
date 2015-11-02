(ns resources.datahub.routing.a.b.another-rest-endpoint
(:require
  [datahub.routing.discovery :as d]
  [compojure.api.sweet :refer [GET* POST* api context* swagger-docs]]
  [ring.util.http-response :refer [ok]]
  )
)

(d/route-actions "This is some other REST endpoint to be discovered."
                 (GET* req (ok "Great Success!"))
                 )
