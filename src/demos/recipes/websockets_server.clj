(ns recipes.websockets-server
  (:require [com.stuartsierra.component :as component]
            [om.next.server :as om]
            [om.next.impl.parser :as op]
            [taoensso.timbre :as timbre]
            [untangled.easy-server :as core]
            [untangled.websockets.components.channel-server :as cs]
            [untangled.websockets.protocols :refer [WSListener client-dropped client-added add-listener remove-listener push]]))

(def db
  (atom {:app/users       []
         :app/channels    [{:db/id            :db.temp/channel-1
                            :channel/title    "general"}]
         :default-channel :db.temp/channel-1}))

(defmulti api-read om/dispatch)

(defn- get-from-db [dispatch-key]
  (get @db dispatch-key))

(defmethod api-read :app/users [{:keys [ast query] :as env} dispatch-key params]
  {:value (get-from-db dispatch-key)})

(defmethod api-read :app/channels [{:keys [ast query] :as env} dispatch-key params]
  {:value (get-from-db dispatch-key)})

(defmethod api-read :default-channel [{:keys [ast query]} dispatch-key params]
  {:value (let [chan-id (get @db :default-channel)]
            (some #(when (= chan-id (:db/id %))) (get-in @db :app/channels)))})

(defmethod api-read :default [{:keys [ast query] :as env} dispatch-key params]
  (timbre/error "Unrecognized query " (op/ast->expr ast))
  (throw (ex-info "Unexpected api read." {:dispatch-key dispatch-key})))

(defmulti apimutate om/dispatch)

(defn update-channel [id component]
  (fn [chans msg]
    (reduce (fn [acc chan]
              (if (= (:db/id chan) id)
                (conj acc (update chan component (fnil conj []) msg))
                (conj acc chan)))
      []
      chans)))

(defn notify-others [ws-net cid verb edn]
  (let [clients (:any @(:connected-cids ws-net))]
    (doall (map (fn [id]
                  (timbre/info "Sending message to: " id)
                  (push ws-net id verb edn))
             (disj clients cid)))))

(defmethod apimutate 'message/add [{:keys [cid ws-net] :as env} _ params]
  {:action (fn []
             (swap! db update :app/channels (update-channel (get @db :default-channel) :channel/messages) params)
             (notify-others ws-net cid :message/new params)
             {})})

(defn add-user [channel-id user-map]
  (swap! db update :app/users (fnil conj []) user-map)
  (swap! db update :app/channels (update-channel channel-id :channel/users) user-map))

(defn remove-user [channel-id user-id]
  (swap! db update :app/users
    (fn [users]
      (reduce (fn [acc next]
                (if (= user-id (:db/id next))
                  acc
                  (conj acc next)))
        [] users)))
  (swap! db update :app/channels
    (fn [channels]
      (reduce (fn [acc next]
                (if (= channel-id (:db/id next))
                  (conj acc (update next :channel/users
                              (fn [users]
                                (filterv #(not= user-id (:db/id %)) users))))
                  (conj acc next)))
        [] channels))))

(defmethod apimutate 'user/add [{:keys [cid ws-net] :as env} _ params]
  {:action (fn []
             (let [temp-id (:db/id params)
                   params (assoc params :db/id cid)]
               (add-user (get @db :default-channel) params)
               (notify-others ws-net cid :user/new params)
               {:tempids {temp-id cid}}))})

(defmethod apimutate 'user/remove [{:keys [cid ws-net] :as env} _ params])

(defmethod apimutate 'app/subscribe [{:keys [ws-net cid] :as env} _ {:keys [topic] :as params}]
  {:action (fn []
             {})})

(defmethod apimutate :default [e k p]
  (timbre/error "Unrecognized mutation " k))


(defrecord ChannelListener [channel-server subscriptions]
  WSListener
  (client-dropped [this ws-net cid]
    (swap! subscriptions update :general (fnil disj #{}) cid)
    (remove-user :db.temp/channel-1 cid)
    (notify-others ws-net cid :user/left {:db/id cid}))
  (client-added [this ws-net cid]
    (swap! subscriptions update :general (fnil conj #{}) cid))

  component/Lifecycle
  (start [component]
    (let [component (assoc component
                      :subscriptions (atom {}))]
      (add-listener channel-server component)
      component))
  (stop [component]
    (remove-listener channel-server component)
    (dissoc component :subscriptions :kill-chan)))

(defn make-channel-listener []
  (component/using
    (map->ChannelListener {})
    [:channel-server]))


(defn logging-mutate [env k params]
  (timbre/info "Mutation Request: " k)
  (apimutate env k params))

(defn logging-query [{:keys [ast] :as env} k params]
  (timbre/info "Query: " (op/ast->expr ast))
  (api-read env k params))

(defn make-system []
  (core/make-untangled-server
    :config-path "config/recipe.edn"
    :parser (om/parser {:read logging-query :mutate logging-mutate})
    :parser-injections #{}
    :components {:channel-server  (cs/make-channel-server)
                 :channel-listener (make-channel-listener)}
    :extra-routes {:routes   ["" {["/chsk"] :web-socket}]
                   :handlers {:web-socket cs/route-handlers}}))
