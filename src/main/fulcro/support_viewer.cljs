(ns fulcro.support-viewer
  (:require
    [fulcro.client.primitives :as prim :refer-macros [defui]]
    [fulcro.client.dom :as dom]
    [fulcro.history :as hist]
    [fulcro.client.data-fetch :refer [load]]
    [fulcro.client.core :as core]
    [fulcro.client.mutations :as m :refer [defmutation]]
    yahoo.intl-messageformat-with-locales
    [fulcro.i18n :refer [tr trf]]
    [fulcro.client.network :as net]))

(defonce support-viewer (atom nil))

(defn get-target-app []
  (-> @support-viewer :application))

(defmutation initialize-history
  [params]
  (action [{:keys [state]}]
    (swap! state
      (fn [s]
        (let [req         (:support-request @state)
              app         (get-target-app)
              comments    (:comment req)
              history     (:history req)
              history-nav (hist/history-navigator history)]
          (core/reset-state! @app (::hist/db-after (hist/current-step history-nav)))
          (-> s
            (dissoc :support-request)
            (assoc :comments comments)
            (assoc :history history)
            (assoc :history-nav history-nav)))))))

(defn nav [state-atom history-fn]
  (swap! state-atom update :history-nav history-fn)
  (core/reset-state! @(get-target-app) (-> @state-atom :history-nav hist/current-step ::hist/db-after)))

(defmutation step-forward [params] (action [{:keys [state]}] (nav state hist/focus-next)))
(defmutation step-back [params] (action [{:keys [state]}] (nav state hist/focus-previous)))
(defmutation go-to-beg [params] (action [{:keys [state]}] (nav state hist/focus-start)))
(defmutation go-to-end [params] (action [{:keys [state]}] (nav state hist/focus-end)))

(defmutation toggle-position
  [params]
  (action [{:keys [state]}]
    (let [{:keys [position]} @state
          new-position (cond
                         (= :controls-left position) :controls-right
                         :else :controls-left)]
      (swap! state assoc :position new-position))))

(defui ^:once SupportViewerRoot
  static prim/IQuery
  (query [this] [:ui/react-key :history-nav :client-time :comments])
  Object
  (render [this]
    (let [{:keys [ui/react-key history-nav client-time position comments] :or {ui/react-key "ROOT"}} (prim/props this)
          [current-position frames] (hist/nav-position history-nav)]
      (dom/div #js {:key react-key :className (str "history-controls " (name position))}
        (dom/button #js {:className "toggle-position"
                         :onClick   #(prim/transact! this `[(toggle-position {})])} (tr "<= Reposition =>"))
        (dom/button #js {:className "history-back"
                         :onClick   #(prim/transact! this `[(step-back {})])} (tr "Back"))
        (dom/button #js {:className "history-forward"
                         :onClick   #(prim/transact! this `[(step-forward {})])} (tr "Forward"))
        (dom/hr nil)
        (dom/span #js {:className "frame"} (trf "History offset {f,number} of {end,number} " :f current-position :end frames))
        (dom/span #js {:className "timestamp"} (trf "{ts,date,short} {ts,time,long}" :ts client-time))
        (dom/div #js {:className "user-comments"} comments)
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
                                       :initial-state {:history     {}
                                                       :position    :controls-left
                                                       :client-time (js/Date.)}
                                       :started-callback
                                       (fn [app]
                                         (load app :support-request nil
                                           {:params        {:id (core/get-url-param "id")}
                                            :refresh       [:frames]
                                            :post-mutation `initialize-history}))))})]
    (reset! support-viewer viewer)
    (core/mount viewer SupportViewerRoot support-dom-id)))
