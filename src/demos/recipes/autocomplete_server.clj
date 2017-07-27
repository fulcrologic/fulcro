(ns recipes.autocomplete-server
  (:require [om.next.server :as om]
            [om.next.impl.parser :as op]
            [taoensso.timbre :as timbre]
            [fulcro.easy-server :as core]
            [fulcro.server :refer [defquery-root defquery-entity defmutation]]
            [om.next.impl.parser :as op]))

(defquery-root :error.child/by-id
  (value [env params]
    (throw (ex-info "other read error" {:status 403 :body "Not allowed."}))))

