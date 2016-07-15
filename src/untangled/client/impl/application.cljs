(ns untangled.client.impl.application
  (:require [untangled.client.logging :as log]
            [om.next :as om]
            [untangled.client.impl.data-fetch :as f]
            [cljs.core.async :as async]
            [untangled.client.impl.network :as net]
            [untangled.client.impl.om-plumbing :as plumbing]
            [untangled.i18n.core :as i18n])
  (:require-macros
    [cljs.core.async.macros :refer [go]]))

(defn fallback-handler [{:keys [reconciler]} query]
  (fn [error]
    (swap! (om/app-state reconciler) assoc :untangled/server-error error)
    (if-let [q (plumbing/fallback-query query error)]
      (do (log/warn (log/value-message "Transaction failed. Running fallback." q))
          (om/transact! reconciler q))
      (log/warn "Fallback triggered, but no fallbacks were defined."))))

;; this is here so we can do testing (can mock core async stuff out of the way)
(defn- enqueue [q v] (go (async/>! q v)))

(defn real-send
  "Do a properly-plumbed network send that strips ui attributes from the tx"
  [net tx on-load on-error]
  (net/send net (plumbing/strip-ui tx) on-load on-error))

(defn enqueue-mutations [{:keys [queue] :as app} remote-tx-map cb]
  (let [full-remote-transaction (:remote remote-tx-map)
        fallback (fallback-handler app full-remote-transaction)
        desired-remote-mutations (plumbing/remove-loads-and-fallbacks full-remote-transaction)
        has-mutations? (> (count desired-remote-mutations) 0)
        payload {:query    desired-remote-mutations
                 :on-load  cb
                 :on-error #(fallback %)}]
    (when has-mutations?
      (enqueue queue payload))))

(defn enqueue-reads [{:keys [queue reconciler networking]}]
  (let [parallel-payload (f/mark-parallel-loading reconciler)
        fetch-payload (f/mark-loading reconciler)]
    (doseq [{:keys [query on-load on-error callback-args]} parallel-payload]
      (let [on-load' #(on-load % callback-args)
            on-error' #(on-error % callback-args)]
        (real-send networking query on-load' on-error')))
    (when fetch-payload
      (enqueue queue (assoc fetch-payload :networking networking)))))

(defn server-send
  "Puts queries/mutations (and their corresponding callbacks) onto the send queue. The networking CSP will pull these
  off one at a time and send them through the real networking layer. Reads are guaranteed to *follow* writes."
  [app remote-tx-map cb]
  (enqueue-mutations app remote-tx-map cb)
  (enqueue-reads app))

(defn start-network-sequential-processing
  "Starts a communicating sequential process that sends network requests from the request queue."
  [{:keys [networking queue response-channel]}]
  (letfn [(make-process-response [action callback-args]
            (fn [resp]
              (try (action resp callback-args)
                   (finally (go (async/>! response-channel :complete))))))]
    (go
      (loop [payload (async/<! queue)]
        (let [{:keys [query on-load on-error callback-args]} payload
              on-load (make-process-response on-load callback-args)
              on-error (make-process-response on-error callback-args)]
          (real-send networking query on-load on-error))
        (async/<! response-channel)                         ; expect to block
        (recur (async/<! queue))))))

(defn initialize-internationalization
  "Configured Om to re-render when locale changes."
  [reconciler]
  (remove-watch i18n/*current-locale* :locale)
  (add-watch i18n/*current-locale* :locale (fn [k r o n]
                                             (when (om/mounted? (om/app-root reconciler))
                                               (om/force-root-render! reconciler)))))

(defn sweep-one "Remove not-found keys from m (non-recursive)" [m]
  (cond
    (map? m) (reduce (fn [acc [k v]]
                       (if (= ::plumbing/not-found v) acc (assoc acc k v))) (with-meta {} (meta m)) m)
    (vector? m) (with-meta (mapv sweep-one m) (meta m))
    :else m))

(defn sweep "Remove all of the not-found keys (recursively) from v, stopping at marked leaves (if present)"
  [m]
  (cond
    (plumbing/leaf? m) (sweep-one m)
    (map? m) (reduce (fn [acc [k v]]
                       (if (= ::plumbing/not-found v) acc (assoc acc k (sweep v)))) (with-meta {} (meta m)) m)
    (vector? m) (with-meta (mapv sweep m) (meta m))
    :else m))

(defn sweep-merge
  [target source]
  (reduce (fn [acc [k v]]
            (cond
              (= v ::plumbing/not-found) (dissoc acc k)
              (plumbing/leaf? v) (assoc acc k (sweep-one v))
              (and (map? (get acc k)) (map? v)) (update acc k sweep-merge v)
              :else (assoc acc k (sweep v)))
            ) target source))

(defn generate-reconciler
  "The reconciler's send method calls UntangledApplication/server-send, which itself requires a reconciler with a
  send method already defined. This creates a catch-22 / circular dependency on the reconciler and :send field within
  the reconciler.

  To resolve the issue, we def an atom pointing to the reconciler that the send method will deref each time it is
  called. This allows us to define the reconciler with a send method that, at the time of initialization, has an app
  that points to a nil reconciler. By the end of this function, the app's reconciler reference has been properly set."
  [{:keys [queue] :as app} initial-state parser {:keys [migrate] :or {migrate nil}}]
  (let [rec-atom (atom nil)
        state-migrate (or migrate plumbing/resolve-tempids)
        tempid-migrate (fn [pure _ tempids _]
                         (plumbing/rewrite-tempids-in-request-queue queue tempids)
                         (state-migrate pure tempids))
        initial-state-with-locale (if (= Atom (type initial-state))
                                    (do
                                      (swap! initial-state assoc :ui/locale "en-US")
                                      initial-state)
                                    (assoc initial-state :ui/locale "en-US"))
        config {:state      initial-state-with-locale
                :send       (fn [tx cb]
                              (server-send (assoc app :reconciler @rec-atom) tx cb))
                :migrate    (or migrate tempid-migrate)
                :normalize  true
                :pathopt    true
                :merge-tree sweep-merge
                :parser     parser}
        rec (om/reconciler config)]

    (reset! rec-atom rec)
    rec))

(defn initialize-global-error-callback [app]
  (let [cb-atom (-> app (get-in [:networking :global-error-callback]))]
    (when (= Atom (type cb-atom))
      (swap! cb-atom #(if (fn? %)
                       (partial % (om/app-state (:reconciler app)))
                       (throw (ex-info "Networking error callback must be a function." {})))))))


