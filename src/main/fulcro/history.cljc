(ns fulcro.history
  (:require #?(:clj [clojure.future :refer :all])
                    [fulcro.client.logging :as log]
                    [clojure.spec.alpha :as s]))


;; FIXME: Logging should be a leaf, so we can refer to it...but the import of Om kind of breaks things...

(s/def ::max-size pos-int?)
(s/def ::db-before map?)
(s/def ::db-after map?)
(s/def ::tx vector?)
(s/def ::tx-result (s/or :nil nil? :map map?))
(s/def ::network-result map?)
(s/def ::network-sends (s/map-of keyword? vector?))         ; map of sends that became active due to this tx
(s/def ::history-step (s/keys :req [::db-after ::db-before] :opt [::tx ::tx-result ::network-result ::network-sends]))
(s/def ::history-steps (s/map-of int? ::history-step))
(s/def ::active-remotes (s/map-of keyword? set?))           ; map of remote to the tx-time of any send(s) that are still active
(s/def ::history (s/keys :opt [::active-remotes] :req [::max-size ::history-steps]))

(def max-tx-time #?(:clj Long/MAX_VALUE :cljs 9200000000000000000))

(defn oldest-active-network-request
  "Returns the tx time for the oldest in-flight send that is active. Returns Long/MAX_VALUE if none are active."
  [{:keys [::active-remotes] :as history}]
  ;(assert (s/valid? ::history history))
  (reduce min max-tx-time (apply concat (vals active-remotes))))

(defn gc-history
  "Returns a new history that has been reduced in size to target levels."
  [{:keys [::active-remotes ::max-size ::history-steps] :as history}]
  ;(assert (s/valid? ::history history))
  (if (> (count history-steps) max-size)
    (let [oldest-required-history-step (oldest-active-network-request history)
          current-size                 (count history-steps)
          overage                      (- current-size max-size) ; guaranteed positive by `if` above
          ordered-steps                (sort (keys history-steps))
          proposed-keepers             (drop overage ordered-steps)
          real-keepers                 (if (> (first proposed-keepers) oldest-required-history-step)
                                         (do
                                           (log/info "WARNING: History has grown beyond max size due to network congestion.")
                                           (drop-while (fn [t] (< t oldest-required-history-step)) ordered-steps))
                                         (do
                                           (log/debug (str "Expired " overage " history entries."))
                                           proposed-keepers))]
      (update history ::history-steps select-keys real-keepers))
    history))

(defn compressible-tx [tx] (vary-meta tx assoc ::compressible? true))

(defn compressible-tx?
  "Returns true if the given transaction is marked as compressible."
  [tx]
  (boolean (some-> tx meta ::compressible?)))

(defn last-tx-time
  "Returns the most recent transition edge time recorded in the given history."
  [{:keys [::history-steps] :as history}]
  (reduce max 0 (keys history-steps)))

(defn record-history-step
  "Record a history step in the reconciler. "
  [{:keys [::active-remotes ::max-size ::history-steps] :as history} tx-time {:keys [::tx ::network-result ::network-sends ::db-before ::db-after] :as step}]
  ;(assert (s/valid? ::history-step step))
  ;(assert (s/valid? ::history history))
  (let [last-time     (last-tx-time history)
        gc?           (= 0 (mod tx-time 10))
        last-tx       (get-in history-steps [last-time ::tx] [])
        compressible? (and (compressible-tx? tx) (compressible-tx? last-tx))
        _             (when compressible? (log/debug "Compressing history!"))
        new-history   (cond-> (assoc-in history [::history-steps tx-time] step)
                        compressible? (update ::history-steps dissoc last-time))]
    (log/debug (str "compress? " :a (compressible-tx? last-tx) :b (compressible-tx? tx)))
    (when-not (or (nil? last-tx) (> tx-time last-time))
      (log/error "Time did not move forward! History may have been lost."))
    ;(assert (or (nil? last-tx) (> tx-time last-time)) "Time moved forward.")
    (log/debug (str "History edge created at sequence step: " tx-time))
    (log/debug (str "Current history size: " (count history-steps)))
    (if gc?
      (gc-history new-history)
      new-history)))

(defn new-history [size]
  {::max-size size ::history-steps {} ::active-remotes {}})

(defn ordered-steps
  "Returns the current valid sequence of step times in the given history as a sorted vector."
  [history]
  (-> history ::history-steps keys sort vec))