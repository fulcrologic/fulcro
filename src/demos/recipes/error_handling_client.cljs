(ns recipes.error-handling-client
  (:require
    [untangled.client.core :as uc]
    [untangled.i18n :refer [tr trf]]
    [untangled.client.data-fetch :as df]
    [untangled.client.logging :as log]
    [untangled.client.mutations :as m]
    [om.dom :as dom]
    [om.next :as om :refer [defui]]))

(defmethod m/mutate 'error/mutation [env k params]
  ;; Just send the mutation to the server, which will return an error
  {:remote true :action (constantly nil)})

;; an :error key is injected into the fallback mutation's params argument
(defmethod m/mutate 'button/disable [{:keys [state]} k {:keys [error] :as params}]
  {:action (fn []
             (log/warn "Mutation specific fallback -- disabling button")
             (swap! state assoc-in [:error.child/by-id :singleton :ui/button-disabled] true))})

(defmethod m/mutate 'read/error-log [env k {:keys [error]}]
  {:action (fn [] (log/warn "Read specific fallback: " error))})

(defui ^:once Child
  static uc/InitialAppState
  (initial-state [c params] {})
  static om/IQuery
  ;; you can query for the server-error using a link from any component that composes to root
  (query [_] [[:untangled/server-error '_] :ui/button-disabled :untangled/read-error])
  static om/Ident
  (ident [_ _] [:error.child/by-id :singleton])
  Object
  (render [this]
    (let [{:keys [untangled/server-error ui/button-disabled]} (om/props this)]
      (dom/div nil
        ;; declare a tx/fallback in the same transact call as the mutation
        ;; if the mutation fails, the fallback will be called
        (dom/button #js {:onClick  #(om/transact! this '[(error/mutation) (tx/fallback {:action button/disable})])
                         :disabled button-disabled}
          "Click me for error!")
        (dom/button #js {:onClick #(df/load-field this :untangled/read-error)}
          "Click me for other error!")
        (dom/div nil (str server-error))))))

(def ui-child (om/factory Child))

(defui ^:once Root
  static uc/InitialAppState
  (initial-state [c params] {:child (uc/get-initial-state Child {})})
  static om/IQuery
  (query [_] [:ui/react-key {:child (om/get-query Child)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key child] :or {ui/react-key "ROOT"}} (om/props this)]
      (dom/div #js {:key react-key} (ui-child child)))))


