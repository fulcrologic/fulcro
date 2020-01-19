(ns ^:no-doc com.fulcrologic.fulcro.inspect.preload
  "Namespace to use in your compiler preload in order to enable inspect support during development."
  (:require
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]))

(inspect/install {})
