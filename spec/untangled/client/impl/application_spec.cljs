(ns untangled.client.impl.application-spec
  (:require
    [untangled.client.core :as uc]
    [om.next :as om :refer-macros [defui]]
    [untangled-spec.core :refer-macros [specification behavior assertions provided component when-mocking]]
    untangled.client.impl.built-in-mutations
    [om.dom :as dom]
    [untangled.i18n.core :as i18n]
    [untangled.client.impl.application :as app]
    [cljs.core.async :as async]
    [untangled.client.impl.data-fetch :as f]))

(defui Thing
  static om/Ident
  (ident [this props] [:thing/by-id (:id props)])
  static om/IQuery
  (query [this] [:id :name])
  Object
  (render [this] (dom/div nil "")))

(defui Root
  static om/IQuery
  (query [this] [:ui/react-key :ui/locale {:things (om/get-query Thing)}])
  Object
  (render [this]
    (dom/div nil "")))

(specification "Untangled Application (integration tests)"
  (let [startup-called (atom false)
        state {:things [{:id 1 :name "A"} {:id 2 :name "B"}]}
        callback (fn [app] (reset! startup-called (:initial-state app)))
        unmounted-app (uc/new-untangled-client :initial-state state :started-callback callback)
        app (uc/mount unmounted-app Root "invisible-specs")
        mounted-app-state (om/app-state (:reconciler app))
        reconciler (:reconciler app)]
    (component "Initialization"
      (behavior "returns untangled client app record with"
        (assertions
          "a request queue"
          (type (:queue app)) => cljs.core.async.impl.channels/ManyToManyChannel
          "a response queue"
          (type (:response-channel app)) => cljs.core.async.impl.channels/ManyToManyChannel
          "a reconciler"
          (type reconciler) => om.next/Reconciler
          "a parser"
          (type (:parser app)) => js/Function
          "a marker that the app was initialized"
          (:mounted? app) => true
          "networking support"
          (type (:networking app)) => untangled.client.impl.network/Network
          "calls the callback with the initialized app"
          @startup-called => state
          "normalizes and uses the initial state"
          (get-in @mounted-app-state [:thing/by-id 1]) => {:id 1 :name "A"}
          (get-in @mounted-app-state [:things 0]) => [:thing/by-id 1])))

    (component "Transactions"
      (when-mocking
        (f/mark-loading r) => {:query [:a]}
        (app/tx-payload t app cb) => {:query '[(f)]}
        (app/enqueue q p) =1x=> (do
                                  (assertions
                                    "mutation is sent, and is first"
                                    p => {:query '[(f)]})
                                  true)
        (app/enqueue q p) =1x=> (do
                                  (assertions
                                    "reads are sent, and are last"
                                    (:query p) => [:a])
                                  true)

        (app/server-send {} {:remote '[(a/f) (app/load {})]} (fn []))))

    (component "Changing app :ui/locale"
      (let [react-key (:ui/react-key @mounted-app-state)]
        (reset! i18n/*current-locale* "en")
        (om/transact! reconciler '[(ui/change-locale {:lang "es-MX"})])
        (assertions
          "Changes the i18n locale for translation lookups"
          (deref i18n/*current-locale*) => "es-MX"
          "Updates the react key to ensure render can redraw everything"
          (not= react-key (:ui/react-key @mounted-app-state)) => true)))))
