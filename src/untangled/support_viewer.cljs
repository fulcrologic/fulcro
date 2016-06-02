(ns untangled.support-viewer
  (:require
    [om.next :as om :refer-macros [defui]]
    [om.dom :as dom]
    [untangled.client.data-fetch :refer [load-data]]
    [untangled.client.core :as core]
    [untangled.client.mutations :as m]
    [yahoo.intl-messageformat-with-locales]
    [untangled.i18n :refer-macros [tr trf]]
    [untangled.client.impl.network :as net]))

(defui ^:once SupportViewerRoot
  static om/IQuery
  (query [this] [:ui/react-key :current-position :client-time :frames :position :comments])
  Object
  (render [this]
    (let [{:keys [ui/react-key current-position client-time frames position comments] :or {ui/react-key "ROOT"}} (om/props this)]
      (dom/div #js {:key react-key :className (str "history-controls " (name position))}
        (dom/button #js {:className "toggle-position" :onClick #(om/transact! this '[(support-viewer/toggle-position)])} (tr "<= Reposition =>"))
        (dom/button #js {:className "history-back" :onClick #(om/transact! this '[(support-viewer/step-back)])} (tr "Back"))
        (dom/button #js {:className "history-forward" :onClick #(om/transact! this '[(support-viewer/step-forward)])} (tr "Forward"))
        (dom/hr nil)
        (dom/span #js {:className "frame"} (trf "Frame {f,number} of {end,number} " :f (inc current-position) :end frames))
        (dom/span #js {:className "timestamp"} (trf "{ts,date,short} {ts,time,long}" :ts client-time))
        (dom/div #js {:className "user-comments"} comments)))))

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
        (reset! support (core/mount @support SupportViewerRoot dom-id-or-node))
        @support)))

  (reset-state! [this new-state] (core/reset-state! @application new-state))

  (refresh [this]
    (core/refresh @application)))

(defn start-untangled-support-viewer
  "Create and display a new untangled support viewer on the given app root, with VCR controls to browse through the given history. The support HTML file must include
  a div with app-dom-id (to mount the app) and a div with support-dom-id to mount the viewer controls."
  [support-dom-id AppRoot app-dom-id]
  (let [app (atom (core/new-untangled-client :networking (net/mock-network)))
        viewer (map->SupportViewer {:app-root    AppRoot
                                    :dom-id      app-dom-id
                                    :application app
                                    :support     (atom (core/new-untangled-client
                                                         :initial-state {:history          {}
                                                                         :application      app
                                                                         :position         :controls-left
                                                                         :client-time      (js/Date.)
                                                                         :frames           0
                                                                         :current-position 0}
                                                         :started-callback
                                                         (fn [{:keys [reconciler]}]
                                                           (load-data reconciler `[(:support-request {:id ~(core/get-url-param "id")})]
                                                             :post-mutation 'support-viewer/initialize-history))))})]
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

(defmethod m/mutate 'support-viewer/initialize-history [{:keys [state]} k params]
  {:action
   (fn []
     (swap! state
       (fn [s]
         (let [req (:support-request @state)
               app (:application @state)
               comments (:comment req)
               history (:history req)
               frames (-> history :steps count)
               last-idx (dec frames)]
           (core/reset-state! @app (history-entry history last-idx))
           (-> s
             (dissoc :support-request)
             (assoc :comments comments)
             (assoc :frames frames)
             (assoc :history history)
             (assoc :current-position last-idx))))))})

(defmethod m/mutate 'support-viewer/step-forward [{:keys [state]} k params] {:action #(history-step state inc)})

(defmethod m/mutate 'support-viewer/step-back [{:keys [state]} k params] {:action #(history-step state dec)})

(defmethod m/mutate 'support-viewer/toggle-position [{:keys [state]} k params]
  {:action (fn []
             (let [{:keys [position]} @state
                   new-position (cond
                                  (= :controls-left position) :controls-right
                                  :else :controls-left)]
               (swap! state assoc :position new-position)))})

