(ns untangled.server.impl.components.wrap-defaults
  (:require [com.stuartsierra.component :as component]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [untangled.server.impl.components.handler :as handler :refer [get-pre-hook set-pre-hook!]]))

(defrecord WrapDefaults [handler defaults-config]
  component/Lifecycle
  (start [this]
    (let [pre-hook (get-pre-hook handler)]
      ;; We want wrap-defaults to take precedence.
      (set-pre-hook! handler (comp #(wrap-defaults % defaults-config) pre-hook))
      this))
  (stop [this] this))

(defn make-wrap-defaults
  "Create a component that adds `ring.middleware.defaults/wrap-defaults` to the middleware in the prehook.

  - `defaults-config` - (Optional) The configuration passed to `wrap-defaults`.
  The 0 arity will use `ring.middleware.defaults/site-defaults`."
  ([]
   (make-wrap-defaults site-defaults))
  ([defaults-config]
   (component/using
     (map->WrapDefaults {:defaults-config defaults-config})
     [:handler])))
