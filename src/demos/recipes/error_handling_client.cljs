(ns recipes.error-handling-client
  (:require
    [fulcro.client.core :as fc]
    [fulcro.i18n :refer [tr trf]]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defui]]))

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


