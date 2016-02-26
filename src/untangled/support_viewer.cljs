(ns untangled.support-viewer
  (:require
    [om.next :as om :refer-macros [defui]]
    [om.dom :as dom]
    [untangled.client.core :as core]
    [untangled.client.mutations :as m]
    [untangled.client.impl.network :as net]))

(defui ^:once SupportViewerRoot
  static om/IQuery
  (query [this] [:react-key])
  Object
  (render [this]
    (let [{:keys [react-key] :or {react-key "ROOT"}} (om/props this)]
      (dom/div #js {:key react-key}
        (dom/button #js {:onClick #(om/transact! this '[(support-viewer/step-back)])} "<Back")
        " "
        (dom/button #js {:onClick #(om/transact! this '[(support-viewer/step-forward)])} "Forward>")))))

(defn history-entry [history n]
  (let [steps (:steps history)
        states (:history history)
        state-id (nth steps n (last steps))]
    (get states state-id)))

(defrecord SupportViewer [support dom-id app-root application history]
  core/UntangledApplication
  (mount [this root-component dom-id-or-node]
    (if (:mounted? @support)
      (do (core/refresh this) this)
      (do
        (reset! application (core/mount @application app-root dom-id))
        (core/reset-state! @application (history-entry history 100000))
        (reset! support (core/mount @support SupportViewerRoot dom-id-or-node))
        @support)))

  (reset-state! [this new-state] (core/reset-state! @application new-state))

  (refresh [this]
    (core/refresh @application)))

(defn start-untangled-support-viewer
  "Create and display a new untangled support viewer on the given app root, with VCR controls to browse through the given history. The support HTML file must include
  a div with app-dom-id (to mount the app) and a div with support-dom-id to mount the viewer controls."
  [support-dom-id AppRoot app-dom-id history]
  (let [app (atom (core/new-untangled-client :networking (net/mock-network)))
        max-idx (dec (count (:steps history)))
        viewer (map->SupportViewer {:history     history
                                    :app-root    AppRoot
                                    :dom-id      app-dom-id
                                    :application app
                                    :support     (atom (core/new-untangled-client
                                                         :initial-state {:history          history
                                                                         :application      app
                                                                         :current-position max-idx}
                                                         :networking (net/mock-network)))})]
    (core/mount viewer SupportViewerRoot support-dom-id)))

(defmethod m/mutate 'support-viewer/step-forward [{:keys [state]} k params]
  {:action (fn []
             (let [{:keys [application current-position history]} @state
                   max-idx (dec (count (:steps history)))]
               (swap! state update :current-position (fn [p] (if (< p max-idx) (inc p) p)))
               (core/reset-state! @application (history-entry history (:current-position @state)))))})

(defmethod m/mutate 'support-viewer/step-back [{:keys [state]} k params]
  {:action (fn []
             (let [{:keys [application current-position history]} @state]
               (swap! state update :current-position (fn [p] (if (> p 0) (dec p) p)))
               (core/reset-state! @application (history-entry history (:current-position @state)))))})

