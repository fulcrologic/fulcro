(ns resources.datahub.routing.a.b.discovery-with-routes
  (:require
    [datahub.routing.discovery :as d]
    [compojure.api.sweet :refer [GET* POST* api context* swagger-docs]]
    [ring.util.http-response :refer [ok]]
    )
  )

(d/route-actions "This is some test REST endpoint to be discovered."
                 (GET* req (ok "Some test OK response"))
                 )
