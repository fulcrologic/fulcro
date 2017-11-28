(ns cards.server-error-handling
  (:require
    #?@(:cljs [[devcards.core :as dc :include-macros true]
               [fulcro.client.cards :refer [fulcro-app]]])
    [fulcro.client.core :as fc]
    [fulcro.i18n :refer [tr trf]]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.client.primitives :as prim :refer [defui]]
    [fulcro.server :as server]
    [fulcro.client.dom :as dom]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(server/defmutation error-mutation [env k params]
  ;; Throw a mutation error for the client to handle
  {:action (fn [] (throw (ex-info "Server error" {:status 401 :body "Unauthorized User"})))})

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
(defmutation disable-button [{:keys [error] :as params}]
  (action [{:keys [state]}]
    (log/warn "Mutation specific fallback -- disabling button")
    (swap! state assoc-in [:error.child/by-id :singleton :ui/button-disabled] true)))

(defmutation log-read-error [{:keys [error]}]
  (action [env] (log/warn "Read specific fallback: " error)))

(defui ^:once Child
  static fc/InitialAppState
  (initial-state [c params] {})
  static prim/IQuery
  ;; you can query for the server-error using a link from any component that composes to root
  (query [_] [[:fulcro/server-error '_] :ui/button-disabled :fulcro/read-error])
  static prim/Ident
  (ident [_ _] [:error.child/by-id :singleton])
  Object
  (render [this]
    (let [{:keys [fulcro/server-error ui/button-disabled]} (prim/props this)]
      (dom/div nil
        ;; declare a tx/fallback in the same transact call as the mutation
        ;; if the mutation fails, the fallback will be called
        (dom/button #js {:onClick  #(prim/transact! this `[(error-mutation {}) (df/fallback {:action disable-button})])
                         :disabled button-disabled}
          "Click me for error!")
        (dom/button #js {:onClick #(df/load-field this :fulcro/read-error)}
          "Click me for other error!")
        (dom/div nil (str server-error))))))

(def ui-child (prim/factory Child))

(defui ^:once Root
  static fc/InitialAppState
  (initial-state [c params] {:child (fc/get-initial-state Child {})})
  static prim/IQuery
  (query [_] [:ui/react-key {:child (prim/get-query Child)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key child] :or {ui/react-key "ROOT"}} (prim/props this)]
      (dom/div #js {:key react-key} (ui-child child)))))

#?(:cljs
   (dc/defcard-doc
     "# Error Handling

     This is a full-stack example. To start the server, make sure you're running a normal clj repl:

     (run-demo-server)

     Note that all of the examples share the same server, but the server code is isolated for each using
     namespacing of the queries and mutations.

     "
     (dc/mkdn-pprint-source Child)
     (dc/mkdn-pprint-source Root)))

#?(:cljs
   (dc/defcard error-handling
     "# Error Handling

     NOTE: The error handling stuff needs work...
     "
     (fulcro-app Root
       :started-callback
       (fn [{:keys [reconciler]}]
         ;; specify a fallback mutation symbol as a named parameter after the component or reconciler and query
         (df/load reconciler :data nil {:fallback 'read/error-log}))

       ;; this function is called on *every* network error, regardless of cause
       :network-error-callback
       (fn [state status-code error]
         (log/warn "Global callback:" error " with status code: " status-code)))
     {}
     {:inspect-data true}))
