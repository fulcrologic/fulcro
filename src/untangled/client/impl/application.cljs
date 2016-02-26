(ns untangled.client.impl.application
  (:require [untangled.client.impl.om-plumbing :as impl]
            [goog.dom :as gdom]
            [untangled.client.logging :as log]
            [om.next :as om]
            [untangled.client.data-fetch :as f]
            [cljs.core.async :as async]
            [untangled.client.impl.network :as net]
            [untangled.client.impl.om-plumbing :as plumbing]
            [untangled.i18n.core :as i18n]
            [untangled.client.impl.util :as util])
  (:require-macros
    [cljs.core.async.macros :refer [go]]))

(defn fallback-handler [{:keys [reconciler]} query]
  (fn [resp]
    ;; TODO: integrate resp into the transaction somehow
    (if-let [q (impl/fallback-query query)]
      (do (log/warn (log/value-message "Transaction failed. Running fallback." q))
          (om/transact! reconciler q))
      (log/warn "Fallback triggered, but no fallbacks were defined."))))

(defn tx-payload [general-tx app cb]
  (let [fallback (fallback-handler app general-tx)]
    {:query    general-tx
     :on-load  #(-> % (impl/mark-missing general-tx) cb)
     :on-error #(fallback %)}))

(defn server-send
  "Puts queries/mutations (and their corresponding callbacks) onto the send queue. The networking CSP will pull these
  off one at a time and send them through the real networking layer."
  [{:keys [reconciler networking queue] :as app} {:keys [remote]} cb]
  (let [general-tx (impl/filter-loads-and-fallbacks remote)
        has-non-fetch-tx? (> (count general-tx) 0)
        fetch-payload (f/mark-loading reconciler)]

    (when fetch-payload
      (go (async/>! queue (assoc fetch-payload :networking networking))))

    (when has-non-fetch-tx?
      (let [payload (tx-payload general-tx app cb)]
        (go (async/>! queue payload))))))

(defn start-network-sequential-processing
  "Starts a communicating sequential process that sends network requests from the request queue."
  [{:keys [networking queue response-channel]}]
  (letfn [(make-process-response [action]
            (fn [resp]
              (try (action resp) (finally (go (async/>! response-channel :complete))))))]
    (go
      (loop [payload (async/<! queue)]
        (let [{:keys [query on-load on-error]} payload
              on-load (make-process-response on-load)
              on-error (make-process-response on-error)]
          (net/send networking (plumbing/strip-ui query) on-load on-error))
        (async/<! response-channel)                         ; expect to block
        (recur (async/<! queue))))))

(defn initialize-internationalization
  "Configured Om to re-render when locale changes."
  [reconciler]
  (remove-watch i18n/*current-locale* :locale)
  (add-watch i18n/*current-locale* :locale (fn [k r o n]
                                             (when (om/mounted? (om/app-root reconciler))
                                               (om/force-root-render! reconciler)))))

(defn generate-reconciler
  "The reconciler's send method calls UntangledApplication/server-send, which itself requires a reconciler with a
  send method already defined. This creates a catch-22 / circular dependency on the reconciler and :send field within
  the reconciler.

  To resolve the issue, we def an atom pointing to the reconciler that the send method will deref each time it is
  called. This allows us to define the reconciler with a send method that, at the time of initialization, has an app
  that points to a nil reconciler. By the end of this function, the app's reconciler reference has been properly set."
  [{:keys [queue] :as app} initial-state parser]
  (let [rec-atom (atom nil)
        tempid-migrate (fn [pure _ tempids _]
                         (impl/rewrite-tempids-in-request-queue queue tempids)
                         (impl/resolve-tempids pure tempids))
        config {:state      initial-state
                :send       (fn [tx cb]
                              (server-send (assoc app :reconciler @rec-atom) tx cb))
                :migrate    tempid-migrate
                :normalize  true
                :merge-tree (comp impl/sweep-missing util/deep-merge)
                :parser     parser}
        rec (om/reconciler config)]

    (reset! rec-atom rec)
    rec))

(defn initialize
  "Initialize the untangled Application. Creates network queue, sets up i18n, creates reconciler, mounts it, and returns
  the initialized app"
  [{:keys [networking started-callback] :as app} initial-state root-component dom-id-or-node]
  (let [queue (async/chan 1024)
        rc (async/chan)
        parser (om/parser {:read impl/read-local :mutate impl/write-entry-point})
        initial-app (assoc app :queue queue :response-channel rc :parser parser :mounted? true
                               :networking networking)
        rec (generate-reconciler initial-app initial-state parser)
        completed-app (assoc initial-app :reconciler rec)
        node (if (string? dom-id-or-node)
               (gdom/getElement dom-id-or-node)
               dom-id-or-node)]
    (js/console.log "initialized: " dom-id-or-node " on " node " with root " root-component)
    (initialize-internationalization rec)
    (start-network-sequential-processing completed-app)
    (om/add-root! rec root-component node)
    (when started-callback
      (started-callback completed-app))
    completed-app))
