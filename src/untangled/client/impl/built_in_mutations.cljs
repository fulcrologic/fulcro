(ns untangled.client.impl.built-in-mutations
  (:require [untangled.client.mutations :refer [mutate post-mutate]]
            [untangled.client.logging :as log]
            [untangled.client.impl.data-fetch :as df]
            [untangled.dom :refer [unique-key]]
            [untangled.i18n.core :as i18n]))

(defmethod mutate 'untangled/load [{:keys [state]} _ {:keys [root field query params without post-mutation fallback ident callback parallel]}]
  (when callback (log/error "Callback no longer supported. Use post-mutation instead."))
  (when (and post-mutation (not (symbol? post-mutation))) (log/error "post-mutation must be a symbol or nil"))
  {:remote true
   :action (fn []
             (df/mark-ready
               :state state
               :root root
               :field field
               :ident ident
               :query query
               :params params
               :without without
               :parallel parallel
               :post-mutation post-mutation
               :fallback fallback))})

(defmethod mutate 'ui/change-locale [{:keys [state]} _ {:keys [lang]}]
  {:action (fn []
             (reset! i18n/*current-locale* lang)
             (swap! state #(-> %
                            (assoc :ui/locale lang)
                            (assoc :ui/react-key (unique-key)))))})

(defmethod mutate 'tx/fallback [env _ {:keys [action execute] :as params}]
  (if execute
    {:action #(some-> (mutate env action (dissoc params :action :execute)) :action (apply []))}
    {:remote true}))

(defmethod mutate 'ui/set-props [{:keys [state ref]} _ params]
  (when (nil? ref) (log/error "ui/set-props requires component to have an ident."))
  {:action #(swap! state update-in ref (fn [st] (merge st params)))})

(defmethod mutate 'ui/toggle [{:keys [state ref]} _ {:keys [field]}]
  (when (nil? ref) (log/error "ui/toggle requires component to have an ident."))
  {:action #(swap! state update-in (conj ref field) not)})

(defmethod mutate :default [{:keys [target]} k _]
  (when (nil? target)
    (log/error (log/value-message "Unknown app state mutation. Have you required the file with your mutations?" k))))

(defmethod post-mutate :default [env k p] nil)
