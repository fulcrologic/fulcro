(ns untangled.client.impl.built-in-mutations
  (:require [untangled.client.mutations :refer [mutate post-mutate]]
            [untangled.client.logging :as log]
            [untangled.client.impl.data-fetch :as df]
            [untangled.dom :refer [unique-key]]
            [untangled.i18n :as i18n]))

; Built-in mutation for adding a remote query to the network requests.
(defmethod mutate 'untangled/load
  [{:keys [state]} _ {:keys [post-mutation remote]
                      :as   config}]
  (when (and post-mutation (not (symbol? post-mutation))) (log/error "post-mutation must be a symbol or nil"))
  {(if remote remote :remote) true
   :action                    (fn [] (df/mark-ready (assoc config :state state)))})

; Built-in i18n mutation for changing the locale of the application. Causes a re-render.
(defmethod mutate 'ui/change-locale [{:keys [state]} _ {:keys [lang]}]
  {:action (fn []
             (reset! i18n/*current-locale* lang)
             (swap! state #(-> %
                             (assoc :ui/locale lang)
                             (assoc :ui/react-key (unique-key)))))})

; A mutation that requests the installation of a fallback mutation on a transaction that should run if that transaction
; fails in a 'hard' way (e.g. network/server error). Data-related error handling should either be implemented as causing
; such a hard error, or as a post-mutation step.
(defmethod mutate 'tx/fallback [env _ {:keys [action execute] :as params}]
  (if execute
    {:action #(some-> (mutate env action (dissoc params :action :execute)) :action (apply []))}
    {:remote true}))

; A convenience helper, generally used 'bit twiddle' the data on a particular database table (using the component's ident).
; Specifically, merge the given `params` into the state of the database object at the component's ident.
; In general, it is recommended this be used for ui-only properties that have no real use outside of the component.
(defmethod mutate 'ui/set-props [{:keys [state ref]} _ params]
  (when (nil? ref) (log/error "ui/set-props requires component to have an ident."))
  {:action #(swap! state update-in ref (fn [st] (merge st params)))})

; A helper method that toggles the true/false nature of a component's state by ident.
; Use for local UI data only. Use your own mutations for things that have a good abstract meaning.
(defmethod mutate 'ui/toggle [{:keys [state ref]} _ {:keys [field]}]
  (when (nil? ref) (log/error "ui/toggle requires component to have an ident."))
  {:action #(swap! state update-in (conj ref field) not)})

(defmethod mutate :default [{:keys [target]} k _]
  (when (nil? target)
    (log/error (log/value-message "Unknown app state mutation. Have you required the file with your mutations?" k))))

;
(defmethod post-mutate :default [env k p] nil)
