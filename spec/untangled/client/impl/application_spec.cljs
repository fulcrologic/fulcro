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
        app (uc/mount unmounted-app Root "application-mount-point")
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
          (get-in @mounted-app-state [:things 0]) => [:thing/by-id 1]
          "sets the language to en-US"
          (get @mounted-app-state :ui/locale) => "en-US")))

    (component "Remote transaction"
      (behavior "are split into reads, mutations, and tx fallbacks"
        (let [real-tx-payload app/mutation-payload
              full-tx '[(a/f) (untangled/load {}) (tx/fallback {:action app/fix-error})]]
          (when-mocking
            (f/mark-loading r) => {:query '[:some-real-query]}
            (app/fallback-handler app tx) => (do
                                               (assertions
                                                 "Fallback handler sees the tx that includes the fallback"
                                                 tx => full-tx))
            (app/mutation-payload tx mtx app cb) => (let [rv (real-tx-payload tx mtx app cb)]
                                                (assertions
                                                  "tx payload sees the full transaction"
                                                  tx => full-tx
                                                  "is given the tx with real mutations only"
                                                  mtx => '[(a/f)]
                                                  "gives back the pure mutations as the payload query"
                                                  (:query rv) => '[(a/f)])
                                                rv)
            (app/enqueue q p) =1x=> (do
                                      (assertions
                                        "mutation is sent, is first, and does not include load/fallbacks"
                                        (:query p) => '[(a/f)])
                                      true)
            (app/enqueue q p) =1x=> (do
                                      (assertions
                                        "reads are sent, and are last"
                                        (:query p) => '[:some-real-query])
                                      true)

            (app/server-send {} {:remote full-tx} (fn []))))))

    (component "Changing app :ui/locale"
      (let [react-key (:ui/react-key @mounted-app-state)]
        (reset! i18n/*current-locale* "en")
        (om/transact! reconciler '[(ui/change-locale {:lang "es-MX"})])
        (assertions
          "Changes the i18n locale for translation lookups"
          (deref i18n/*current-locale*) => "es-MX"
          "Places the new locale in the app state"
          (:ui/locale @mounted-app-state) => "es-MX"
          "Updates the react key to ensure render can redraw everything"
          (not= react-key (:ui/react-key @mounted-app-state)) => true)))))

(specification "")

