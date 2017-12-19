(ns cards.server-error-handling
  (:require
    #?@(:cljs [[devcards.core :as dc :include-macros true]
               [fulcro.client.cards :refer [defcard-fulcro]]])
    [fulcro.client :as fc]
    [fulcro.i18n :refer [tr trf]]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.server :as server]
    [fulcro.client.dom :as dom]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(server/defmutation error-mutation [params]
  ;; Throw a mutation error for the client to handle
  (action [env] (throw (ex-info "Server error" {:status 401 :body "Unauthorized User"}))))

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
  (dom/div nil
    ;; declare a tx/fallback in the same transact call as the mutation
    ;; if the mutation fails, the fallback will be called
    (dom/button #js {:onClick  #(prim/transact! this `[(error-mutation {}) (df/fallback {:action disable-button})])
                     :disabled button-disabled}
      "Click me for error!")
    (dom/button #js {:onClick #(df/load-field this :fulcro/read-error)}
      "Click me for other error!")
    (dom/div nil (str server-error))))

(def ui-child (prim/factory Child))

(defsc Root [this {:keys [ui/react-key child] :or {ui/react-key "ROOT"}}]
  {:initial-state (fn [params] {:child (prim/get-initial-state Child {})})
   :query         [:ui/react-key {:child (prim/get-query Child)}]}
  (dom/div #js {:key react-key} (ui-child child)))

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
   (defcard-fulcro error-handling
     "# Error Handling

     This example has installed a global handler that can watch for network errors (it logs them to the console).

     On startup it attempts to read garbage (which generates an error and triggers a fallback). This will leave a load
     marker in app state, which can be examined to show an actual UI error.

     Each button tries a different mutation. The one on the left disables itself on errors, whereas the right
     one allows you to keep trying.

     In all cases the application internals place the last server error in the top-level `:fulcro/server-error`. You
     should manually clear this if you wish to use it to track hard server errors.
     "
     Root
     {}
     {:fulcro       {:started-callback       (fn [{:keys [reconciler]}]
                                               ;; specify a fallback mutation symbol as a named parameter after the component or reconciler and query
                                               (df/load reconciler :data nil {:fallback `log-read-error}))
                     ;; this function is called on *every* network error, regardless of cause
                     :network-error-callback (fn [state status-code error]
                                               (log/warn "Global callback:" error " with status code: " status-code))}
      :inspect-data true}))
