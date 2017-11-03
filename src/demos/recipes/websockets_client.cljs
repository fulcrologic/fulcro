(ns recipes.websockets-client
  (:require
    [fulcro.client.primitives :as prim]
    [fulcro.client.core :as fc]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.mutations :as m]
    [fulcro.websockets.networking :as wn]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer-macros [defui]] ))

(defui ^:once User
  static prim/IQuery
  (query [_] [:db/id :user/name])

  static prim/Ident
  (ident [_ {:keys [db/id]}] [:user/by-id id]))

(def ui-user (prim/factory User {:keyfn :db/id}))

(defui ^:once Message
  static prim/IQuery
  (query [_] [:db/id
              :message/text
              {:message/user (prim/get-query User)}])

  static prim/Ident
  (ident [_ {:keys [db/id]}] [:message/by-id id])

  Object
  (render [this]
    (let [{:keys [db/id
                  message/text
                  message/user]} (prim/props this)]
      (dom/li #js {}
        (dom/div nil
          (dom/span nil
            (dom/strong nil (:user/name user))
            (dom/span nil (str " - " text))))))))

(def ui-message (prim/factory Message {:keyfn identity}))

(defui ^:once Channel
  static prim/IQuery
  (query [_] [:db/id
              :channel/title
              {:channel/users (prim/get-query User)}
              {:channel/messages (prim/get-query Message)}
              {[:current-user '_] (prim/get-query User)}])

  static prim/Ident
  (ident [_ {:keys [db/id]}] [:channel/by-id id])

  Object
  (initLocalState [this]
    {:new-message ""})

  (render [this]
    (let [{:keys [channel/title
                  channel/users
                  channel/messages
                  current-user]} (prim/props this)
          {:keys [new-message]} (prim/get-state this)]
      (dom/div nil
        (dom/h4 nil
          (str "Channel - " title))
        (dom/h5 nil
          (clojure.string/join " " (conj (map :user/name users) "Active users: ")))
        (dom/ul nil
          (map ui-message messages))
        (dom/div nil
          (dom/input #js {:type     "text"
                          :value    new-message
                          :onChange #(prim/update-state! this assoc :new-message (.. % -target -value))})
          (dom/button #js {:onClick (fn []
                                      (prim/update-state! this assoc :new-message "")
                                      (prim/transact! this `[(message/add ~{:db/id        (prim/tempid)
                                                                          :message/text new-message
                                                                          :message/user current-user})]))}
            "Send"))))))

(def ui-channel (prim/factory Channel {:keyfn :db/id}))

(defui ^:once Root
  static prim/IQuery
  (query [this] [:ui/react-key
                 {:current-user (prim/get-query User)}
                 {:current-channel (prim/get-query Channel)}
                 {:app/users (prim/get-query User)}
                 {:app/channels (prim/get-query Channel)}])

  Object
  (initLocalState [this] {:new-user ""})
  (render [this]
    (let [{:keys [ui/react-key data app/channels app/users current-user current-channel]
           :or   {ui/react-key "ROOT"}} (prim/props this)
          {:keys [new-user]} (prim/get-state this)
          validUserName (some #(= new-user (:user/name %)) users)]
      (dom/div #js {:key react-key}
        (if (empty? current-user)
          (dom/div #js {}
            (dom/header nil
              "Get signed in: ")
            (dom/input #js {:type     "text"
                            :value    new-user
                            :onChange #(prim/update-state! this assoc :new-user (.. % -target -value))})
            (dom/button #js {:disabled validUserName
                             :onClick  #(prim/transact! this `[(user/add ~{:db/id     (prim/tempid)
                                                                         :user/name new-user})])}
              "Sign in"))
          (dom/div #js {}
            (dom/h3 #js {}
              (str "Fulcro Chat - " (:user/name current-user)))
            (ui-channel current-channel)))))))

;;; PUSH MUTATIONS

(defmethod m/mutate 'push/user-left [{:keys [state ast] :as env} _ {:keys [msg]}]
  {:action (fn []
             (let [channel-ident (get @state :current-channel)
                   user-ident    [:user/by-id (:db/id msg)]]
               (swap! state update :user/by-id dissoc (:db/id msg))
               (swap! state update :app/users
                 (fn [users] (into [] (remove #(= user-ident %)) users)))
               (swap! state update-in (conj channel-ident :channel/users)
                 (fn [users] (into [] (remove #(= user-ident %)) users)))))})

(defmethod m/mutate 'push/user-new [{:keys [state ast] :as env} _ {:keys [msg]}]
  {:action (fn []
             (let [channel-ident (get @state :current-channel)
                   user-ident    [:user/by-id (:db/id msg)]]
               (swap! state assoc-in user-ident msg)
               (swap! state update :app/users (fnil conj []) user-ident)
               (swap! state update-in (conj channel-ident :channel/users) (fnil conj []) user-ident)))})

(defmethod m/mutate 'push/message-new [{:keys [state ast] :as env} _ {:keys [msg]}]
  {:action (fn []
             (let [channel-ident (get @state :current-channel)
                   message-ident [:message/by-id (:db/id msg)]]
               (swap! state assoc-in message-ident msg)
               (swap! state update-in (conj channel-ident :channel/messages) (fnil conj []) message-ident)))})

;;; CLIENT MUTATIONS

(defmethod m/mutate 'channel/set [{:keys [state ast] :as env} _ params]
  {:action (fn []
             (swap! state assoc :current-channel params))})

(defmethod m/mutate 'user/add [{:keys [state ast] :as env} _ params]
  {:remote ast
   :action (fn []
             (let [{:keys [db/id]} params
                   ident    [:user/by-id id]
                   def-chan (first (-> @state :app/channels))]
               (swap! state assoc-in ident params)
               (swap! state assoc :current-channel def-chan :current-user ident)
               (swap! state update :app/users (fnil conj []) ident)
               (swap! state update-in [:channel/by-id (second def-chan)]
                 (fn [chan ident]
                   (update chan :channel/users (fnil conj []) ident))
                 ident))
             {})})

(defmethod m/mutate 'message/add [{:keys [state ast] :as env} _ params]
  {:remote ast
   :action (fn []
             (let [channel-ident (get @state :current-channel)
                   {:keys [db/id]} params
                   ident         [:message/by-id id]]
               (swap! state assoc-in ident params)
               (swap! state update-in (conj channel-ident :channel/messages) (fnil conj []) ident))
             {})})

(defmethod m/mutate 'app/subscribe [{:keys [ast]} _ _]
  {:remote ast})

(defmethod wn/push-received :user/left [{:keys [reconciler] :as app} {:keys [msg]}]
  (prim/transact! reconciler `[(push/user-left ~{:msg msg})]))

(defmethod wn/push-received :user/new [{:keys [reconciler] :as app} {:keys [msg]}]
  (prim/transact! reconciler `[(push/user-new ~{:msg msg})]))

(defmethod wn/push-received :message/new [{:keys [reconciler] :as app} {:keys [msg]}]
  (prim/transact! reconciler `[(push/message-new ~{:msg msg})]))
