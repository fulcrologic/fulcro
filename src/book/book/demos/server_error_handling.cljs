(ns book.demos.server-error-handling
  (:require
    [fulcro.client :as fc]
    [fulcro.client.data-fetch :as df]
    [fulcro.logging :as log]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.server :as server]
    [fulcro.client.dom :as dom]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(server/defmutation error-mutation [params]
  ;; Throw a mutation error for the client to handle
  (action [env] (throw (ex-info "Server error" {:type :fulcro.client.primitives/abort :status 401 :body "Unauthorized User"}))))

(server/defquery-entity :error.child/by-id
  (value [env id params]
    (throw (ex-info "other read error" {:status 403 :body "Not allowed."}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLIENT:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Just send the mutation to the server, which will return an error
(defmutation error-mutation [params]
  (remote [env] true))

;; an :error key is injected into the fallback mutation's params argument
(defmutation disable-button [{:keys [error ::prim/ref] :as params}]
  (action [{:keys [state]}]
    (log/warn "Mutation specific fallback -- disabling button due to error from mutation invoked at " ref)
    (swap! state assoc-in [:error.child/by-id :singleton :ui/button-disabled] true)))

(defmutation log-read-error [{:keys [error]}]
  (action [env] (log/warn "Read specific fallback: " error)))

(defsc Child [this {:keys [fulcro/server-error ui/button-disabled]}]
  ;; you can query for the server-error using a link from any component that composes to root
  {:initial-state (fn [p] {})
   :query         (fn [] [[:fulcro/server-error '_] :ui/button-disabled :fulcro/read-error])
   :ident         (fn [] [:error.child/by-id :singleton])}  ; lambda so we get a *literal* ident
  (dom/div
    ;; declare a tx/fallback in the same transact call as the mutation
    ;; if the mutation fails, the fallback will be called
    (dom/button {:onClick #(df/load this :data nil {:fallback `log-read-error})}
      "Click me to try a read with a fallback (logs to console)")
    (dom/button {:onClick  #(prim/transact! this `[(error-mutation {}) (df/fallback {:action disable-button})])
                 :disabled button-disabled}
      "Click me for error (disables on error)!")
    (dom/button {:onClick #(df/load-field this :fulcro/read-error)}
      "Click me for other error!")
    (dom/div "Server error (root level): " (str server-error))))

(def ui-child (prim/factory Child))

(defsc Root [this {:keys [child]}]
  {:initial-state (fn [params] {:child (prim/get-initial-state Child {})})
   :query         [{:child (prim/get-query Child)}]}
  (dom/div (ui-child child)))

