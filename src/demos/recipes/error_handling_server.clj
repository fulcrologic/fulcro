(ns recipes.error-handling-server
  (:require [om.next.server :as om]
            [om.next.impl.parser :as op]
            [taoensso.timbre :as timbre]
            [untangled.easy-server :as core]
            [cards.server-api :as api :refer [server-read server-mutate]]
            [om.next.impl.parser :as op]))

(defmethod server-mutate 'recipes.error-handling-client/error-mutation [env k params]
  ;; Throw a mutation error for the client to handle
  {:action (fn [] (throw (ex-info "Server error" {:status 401 :body "Unauthorized User"})))})

(defmethod server-read :error.child/by-id [{:keys [ast]} _ _]
  {:value (throw (ex-info "other read error" {:status 403 :body "Not allowed."}))})


