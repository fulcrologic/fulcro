(ns fulcro.support-viewer
  (:require
    [fulcro.client.primitives :as prim :refer-macros [defui]]
    [fulcro.client.dom :as dom]
    [fulcro.client.data-fetch :refer [load]]
    [fulcro.client.core :as core]
    [fulcro.client.mutations :as m :refer [defmutation]]
    yahoo.intl-messageformat-with-locales
    [fulcro.i18n :refer [tr trf]]
    [fulcro.client.network :as net]))

(defonce support-viewer (atom nil))

(defn get-target-app []
  (-> @support-viewer :application))

(defn history-entry [history n]
  (let [steps    (:steps history)
        states   (:history history)
        state-id (nth steps n (last steps))]
    (get states state-id)))

(defn history-step [state delta-fn]
  (let [{:keys [history playback-speed]} @state
        application    (get-target-app)
        max-idx        (dec (count (:steps history)))
        p              (:current-position @state)
        playback-speed (max 1 playback-speed)               ;; Playback speed min is 1.
        new-pos        (-> p
                         delta-fn
                         (- p)                              ; Get the delta i.e. (p' - p).
                         (* playback-speed)                 ; Multiply delta by the playback speed.
                         (+ p)                              ; Apply this new delta to p.
                         (max 0)
                         (min max-idx))
        entry          (history-entry history new-pos)
        tm             (-> entry :fulcro/meta :client-time)]
    (swap! state (fn [s] (-> s
                           (assoc :current-position new-pos)
                           (assoc :client-time tm))))
    (core/reset-state! @application entry)))

(defmutation initialize-history
  [params]
  (action [{:keys [state]}]
    (swap! state
      (fn [s]
        (let [req      (:support-request @state)
              app      (get-target-app)
              comments (:comment req)
              history  (:history req)
              frames   (-> history :steps count)
              last-idx (dec frames)]
          (core/reset-state! @app (history-entry history last-idx))
          (-> s
            (dissoc :support-request)
            (assoc :comments comments)
            (assoc :frames frames)
            (assoc :history history)
            (assoc :current-position last-idx)))))))

(defmutation step-forward
  [params]
  (action [{:keys [state]}] (history-step state inc)))

(defmutation step-back
  [params]
  (action [{:keys [state]}] (history-step state dec)))

(defmutation toggle-position
  [params]
  (action [{:keys [state]}]
    (let [{:keys [position]} @state
          new-position (cond
                         (= :controls-left position) :controls-right
                         :else :controls-left)]
      (swap! state assoc :position new-position))))

(defmutation go-to-beg
  [params]
  (action [{:keys [state]}] (history-step state (fn [pos] 0))))

(defmutation go-to-end
  [params]
  (action [{:keys [state]}] (let [steps (-> @state :history :steps count dec)]
                              (history-step state (fn [pos] steps)))))

(defmutation update-playback-speed
  [{:keys [playback-speed]}]
  (action [{:keys [state]}] (swap! state assoc :playback-speed playback-speed)))

(defui ^:once SupportViewerRoot
  static prim/IQuery
  (query [this] [:ui/react-key :playback-speed :current-position :client-time :frames :position :comments])
  Object
  (render [this]
    (let [{:keys [ui/react-key playback-speed current-position client-time frames position comments] :or {ui/react-key "ROOT"}} (prim/props this)]
      (dom/div #js {:key react-key :className (str "history-controls " (name position))}
        (dom/button #js {:className "toggle-position"
                         :onClick   #(prim/transact! this `[(toggle-position {})])} (tr "<= Reposition =>"))
        (dom/button #js {:className "history-back"
                         :onClick   #(prim/transact! this `[(step-back {})])} (tr "Back"))
        (dom/button #js {:className "history-forward"
                         :onClick   #(prim/transact! this `[(step-forward {})])} (tr "Forward"))
        (dom/hr nil)
        (dom/span #js {:className "frame"} (trf "Frame {f,number} of {end,number} " :f (inc current-position) :end frames))
        (dom/span #js {:className "timestamp"} (trf "{ts,date,short} {ts,time,long}" :ts client-time))
        (dom/div #js {:className "user-comments"} comments)
        (dom/hr nil)
        (dom/span #js {:className "playback-speed"} (trf "Playback speed {s,number}" :s playback-speed))
        (dom/div #js {}
          (dom/button #js {:className "speed-1"
                           :onClick   #(prim/transact! this `[(update-playback-speed {:playback-speed 1})])} (tr "1x"))
          (dom/button #js {:className "speed-10"
                           :onClick   #(prim/transact! this `[(update-playback-speed {:playback-speed 10})])} (tr "10x"))
          (dom/button #js {:className "speed-25"
                           :onClick   #(prim/transact! this `[(update-playback-speed {:playback-speed 25})])} (tr "25x")))
        (dom/hr nil)
        (dom/span #js {:className "history-jump-to"} "Jump to:")
        (dom/div #js {}
          (dom/button #js {:className "history-beg"
                           :onClick   #(prim/transact! this `[(go-to-beg {})])} (tr "Beginning"))
          (dom/button #js {:className "history-end"
                           :onClick   #(prim/transact! this `[(go-to-end {})])} (tr "End")))))))

(defrecord SupportViewer [support dom-id app-root application history]
  core/FulcroApplication
  (mount [this support-root dom-id-or-node]
    (do
      (reset! application (core/mount @application app-root dom-id))
      (reset! support (core/mount @support support-root dom-id-or-node))
      @support))

  (reset-state! [this new-state] (core/reset-state! @application new-state))

  (refresh [this]
    (core/refresh @application)))

(defn start-fulcro-support-viewer
  "Create and display a new fulcro support viewer on the given app root, with VCR controls to browse through the given history. The support HTML file must include
  a div with app-dom-id (to mount the app) and a div with support-dom-id to mount the viewer controls."
  [support-dom-id AppRoot app-dom-id]
  (let [viewer (map->SupportViewer
                 {:app-root    AppRoot
                  :dom-id      app-dom-id
                  :application (atom (core/new-fulcro-client :networking (net/mock-network)))
                  :support     (atom (core/new-fulcro-client
                                       :initial-state {:history          {}
                                                       :position         :controls-left
                                                       :client-time      (js/Date.)
                                                       :playback-speed   1
                                                       :frames           0
                                                       :current-position 0}
                                       :started-callback
                                       (fn [app]
                                         (load app :support-request nil
                                           {:params        {:id (core/get-url-param "id")}
                                            :refresh       [:frames]
                                            :post-mutation `initialize-history}))))})]
    (reset! support-viewer viewer)
    (core/mount viewer SupportViewerRoot support-dom-id)))
