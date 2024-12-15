(ns ^:no-doc com.fulcrologic.fulcro.inspect.preload
  "Namespace to use in your compiler preload in order to enable inspect support during development."
  (:require
    [taoensso.timbre :as log]))

(log/error "Inspect NOT installed. This version of Fulcro requires you use fulcro inspect as a dev-time dependency, and explicitly call (fulcro.inspect.tool/add-fulcro-inspect! app) on your app,,")
(log/error "NOTE: You will need to also add the preload: com.fulcrologic.devtools.chrome-preload to use Fulcro Inspect Chrome")
