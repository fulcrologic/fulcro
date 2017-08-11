(ns recipes.error-handling-server
  (:require [fulcro.server :refer [defquery-root defquery-entity defmutation server-mutate]]))

(defmethod server-mutate 'recipes.error-handling-client/error-mutation [env k params]
  ;; Throw a mutation error for the client to handle
  {:action (fn [] (throw (ex-info "Server error" {:status 401 :body "Unauthorized User"})))})

(defquery-entity :error.child/by-id
  (value [env id params]
    (throw (ex-info "other read error" {:status 403 :body "Not allowed."}))))


