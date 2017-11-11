(ns fulcro.history
  (:require #?(:clj [clojure.future :refer :all])
                    [fulcro.client.logging :as log]
                    [fulcro.util :as util]
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
(s/def ::history-atom (s/and #(util/atom? %) #(s/valid? ::history (deref %))))

(def max-tx-time #?(:clj Long/MAX_VALUE :cljs 9200000000000000000))

(defn remote-activity-started
  "Record that remote activity started for the given remote at the given tx-time. Returns a new history."
  [history remote tx-time]
  (update-in history [::active-remotes remote] (fnil conj #{}) tx-time))

(defn remote-activity-finished
  "Record that remote activity finished for the given remote at the given tx-time. Returns a new history."
  [history remote tx-time]
  (update-in history [::active-remotes remote] disj tx-time))

(defn oldest-active-network-request
  "Returns the tx time for the oldest in-flight send that is active. Returns Long/MAX_VALUE if none are active."
  [{:keys [::active-remotes] :as history}]
  (reduce min max-tx-time (apply concat (vals active-remotes))))

(s/fdef oldest-active-network-request
  :args (s/cat :hist ::history)
  :ref int?)

(defn gc-history
  "Returns a new history that has been reduced in size to target levels."
  [{:keys [::active-remotes ::max-size ::history-steps] :as history}]
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

(s/fdef gc-history
  :args (s/cat :hist ::history)
  :ref ::history)

(defn compressible-tx [tx] (vary-meta tx assoc ::compressible? true))

(s/fdef compressible-tx
  :args (s/cat :tx vector?)
  :ret vector?
  :fn #(= (-> % :args :tx) (:ret %)))

(defn compressible-tx?
  "Returns true if the given transaction is marked as compressible."
  [tx]
  (boolean (some-> tx meta ::compressible?)))

(s/fdef compressible-tx?
  :args (s/cat :tx vector?)
  :ret boolean?)

(defn last-tx-time
  "Returns the most recent transition edge time recorded in the given history."
  [{:keys [::history-steps] :as history}]
  (reduce max 0 (keys history-steps)))

(defn record-history-step
  "Record a history step in the reconciler. "
  [{:keys [::active-remotes ::max-size ::history-steps] :as history} tx-time {:keys [::tx ::network-result ::network-sends ::db-before ::db-after] :as step}]
  (let [last-time     (last-tx-time history)
        gc?           (= 0 (mod tx-time 10))
        last-tx       (get-in history-steps [last-time ::tx] [])
        compressible? (and (compressible-tx? tx) (compressible-tx? last-tx))
        new-history   (cond-> (assoc-in history [::history-steps tx-time] step)
                        compressible? (update ::history-steps dissoc last-time))]
    (when-not (or (nil? last-tx) (> tx-time last-time))
      (log/error "Time did not move forward! History may have been lost."))
    (util/soft-invariant (or (nil? last-tx) (> tx-time last-time)) "Time moved forward.")
    (log/debug "History edge created at sequence step: " tx-time)
    (if gc?
      (gc-history new-history)
      new-history)))

(s/fdef record-history-step
  :args (s/cat :hist ::history :time ::tx-time :step ::history-step)
  :ret ::history)


(defn new-history [size]
  {::max-size size ::history-steps {} ::active-remotes {}})

(s/fdef record-history-step
  :args (s/cat :size pos-int?)
  :ret ::history)

(defn ordered-steps
  "Returns the current valid sequence of step times in the given history as a sorted vector."
  [history]
  (some-> history ::history-steps keys sort vec))

(s/fdef ordered-steps
  :args (s/cat :hist ::history)
  :ret (s/or :v vector? :nothing nil?))

(defn get-step
  "Returns a step from the given history that has the given tx-time. If tx-time specifies a spot where there is a gap in the history
  (there are steps before and after), then it will return the earlier step, unless the latter was compressible, in which case
  it will return the step into which the desired spot was compressed. "
  [{:keys [::history-steps] :as history} tx-time]
  (if-let [exact-step (get history-steps tx-time)]
    exact-step
    (let [timeline    (ordered-steps history)
          [before after] (split-with #(> tx-time %) timeline)
          step-before (get history-steps (last before))
          step-after  (get history-steps (first after))]
      (cond
        (and step-before step-after (-> step-after ::tx compressible-tx?)) step-after
        (and step-before step-after) step-before
        :otherwise nil))))

(s/fdef get-step
  :args (s/cat :hist ::history :time ::tx-time)
  :ret (s/or :nothing nil? :step ::history-step))

(defn history-navigator
  "Returns a navigator of history. Use focus-next, focus-previous, and current-step."
  [history]
  (let [steps (ordered-steps history)]
    {:legal-steps steps
     :history     history
     :index       (dec (count steps))}))

(defn focus-next
  "Returns a new history navigation with the focus on the next step (or the last if already there). See history-navigator"
  [history-nav]
  (let [{:keys [index history legal-steps]} history-nav
        last-legal-idx (dec (count legal-steps))]
    (update history-nav :index (fn [i] (if (< i last-legal-idx) (inc i) i)))))

(defn focus-previous
  "Returns a new history navigation with the focus on the prior step (or the first if already there). See history-navigator"
  [history-nav]
  (let [{:keys [index history legal-steps]} history-nav]
    (update history-nav :index (fn [i] (if (zero? i) 0 (dec i))))))

(defn current-step
  "Get the current history step from the history-nav. See history-navigator."
  [history-nav]
  (let [{:keys [index history legal-steps]} history-nav
        history-step-tx-time (get legal-steps index)
        history-step         (get-step history history-step-tx-time)]
    history-step))
