(ns untangled.support-viewer
  (:require
    [om.next :as om :refer-macros [defui]]
    [om.dom :as dom]
    [untangled.client.core :as core]
    [untangled.client.mutations :as m]
    [yahoo.intl-messageformat-with-locales]
    [untangled.i18n :refer-macros [tr trf]]
    [untangled.client.impl.network :as net]))

(defui ^:once SupportViewerRoot
  static om/IQuery
  (query [this] [:react-key :current-position :client-time :frames :position])
  Object
  (render [this]
    (let [{:keys [react-key current-position client-time frames position] :or {react-key "ROOT"}} (om/props this)]
      (dom/div #js {:key react-key :className (str "history-controls " (name position))}
        (dom/button #js {:onClick #(om/transact! this '[(support-viewer/toggle-position)])} (tr "<===>"))
        (dom/button #js {:onClick #(om/transact! this '[(support-viewer/step-back)])} (tr "<Back"))
        (dom/button #js {:onClick #(om/transact! this '[(support-viewer/step-forward)])} (tr "Forward>"))
        (dom/hr nil)
        (dom/span #js {:className "frame"} (trf "Frame {f,number} of {end,number} " :f (inc current-position) :end frames))
        (dom/span #js {:className "timestamp"} (trf "{ts,date,short} {ts,time,long}" :ts client-time))))))

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
        (core/reset-state! @application (history-entry history (-> history :steps count dec)))
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
                                                                         :position        :controls-left
                                                                         :client-time      (js/Date.)
                                                                         :frames           (inc max-idx)
                                                                         :current-position max-idx}
                                                         :networking (net/mock-network)))})]
    (core/mount viewer SupportViewerRoot support-dom-id)))

(defn history-step [state delta-fn]
  (let [{:keys [application history]} @state
        max-idx (dec (count (:steps history)))
        p (:current-position @state)
        new-pos (min max-idx (max 0 (delta-fn p)))
        entry (history-entry history new-pos)
        tm (-> entry :untangled/meta :client-time)]
    (swap! state (fn [s] (-> s
                           (assoc :current-position new-pos)
                           (assoc :client-time tm))))
    (core/reset-state! @application entry)))

(defmethod m/mutate 'support-viewer/step-forward [{:keys [state]} k params] {:action #(history-step state inc)})

(defmethod m/mutate 'support-viewer/step-back [{:keys [state]} k params] {:action #(history-step state dec)})

(defmethod m/mutate 'support-viewer/toggle-position [{:keys [state]} k params]
  {:action (fn []
             (let [{:keys [position]} @state
                   new-position (cond
                                  (= :controls-left position) :controls-right
                                  :else :controls-left)]
               (swap! state assoc :position new-position)))})

