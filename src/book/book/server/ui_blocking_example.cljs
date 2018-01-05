(ns book.server.ui-blocking-example
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.mutations :as m]
            [fulcro.server :as server]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [book.macros :refer [defexample]]))

;; SERVER

(server/defmutation submit-form [params]
  (action [env]
    (if (> 0.5 (rand))
      {:message "Everything went swell!"
       :result  0}
      {:message "There was an error!"
       :result  1})))

;; CLIENT

(defsc BlockingOverlay [this {:keys [ui/active? ui/message]}]
  {:query         [:ui/active? :ui/message]
   :initial-state {:ui/active? false :ui/message "Please wait..."}}
  (dom/div (clj->js {:style {:position        :absolute
                             :display         (if active? "block" "none")
                             :zIndex          65000
                             :width           "400px"
                             :height          "100px"
                             :backgroundColor "rgba(0,0,0,0.5)"}})
    (dom/div (clj->js {:style {:position  :relative
                               :top       "40px"
                               :color     "white"
                               :textAlign "center"}}) message)))

(def ui-overlay (prim/factory BlockingOverlay))

(defn set-overlay-visible* [state tf] (assoc-in state [:overlay :ui/active?] tf))
(defn set-overlay-message* [state message] (assoc-in state [:overlay :ui/message] message))

(defsc MutationStatus [this props]
  {:ident (fn [] [:remote-mutation :status])
   :query [:message :result]})

(defmutation submit-form [params]
  (action [{:keys [state]}] (swap! state set-overlay-visible* true))
  (remote [{:keys [state ast] :as env}]
    (m/returning ast state MutationStatus)))

(defn submit-ok? [env] (= 0 (some-> env :state deref :remote-mutation :status :result)))

(defmutation retry-or-hide-overlay [params]
  (action [{:keys [reconciler state] :as env}]
    (if (submit-ok? env)
      (swap! state (fn [s]
                     (-> s
                       (set-overlay-message* "Please wait...") ; reset the overlay message for the next appearance
                       (set-overlay-visible* false))))
      (do
        (swap! state set-overlay-message* (str (-> state deref :remote-mutation :status :message) " (Retrying...)"))
        (prim/ptransact! reconciler `[(submit-form {}) (retry-or-hide-overlay {})]))))
  (refresh [env] [:overlay])) ; we need this because the mutation runs outside of the context of a component

(defsc Root [this {:keys [ui/name ui/react-key overlay]}]
  {:query         [:ui/react-key :ui/name {:overlay (prim/get-query BlockingOverlay)}]
   :initial-state {:overlay {} :ui/name "Alicia"}}
  (dom/div #js {:key react-key :style (clj->js {:width "400px" :height "100px"})}
    (ui-overlay overlay)
    (dom/p nil "Name: " (dom/input #js {:value name}))
    (dom/button #js {:onClick #(prim/ptransact! this `[(submit-form {}) (retry-or-hide-overlay {})])}
      "Submit")))

