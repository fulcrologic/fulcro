(ns com.fulcrologic.fulcro.application
  (:require
    [com.fulcrologic.fulcro.rendering.ident-optimized-render :as ident-optimized]
    [com.fulcrologic.fulcro.algorithms.scheduling :as sched]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.algorithms.indexing :as indexing]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.misc :as util]
    [com.fulcrologic.fulcro.mutations :as mut]
    [com.fulcrologic.fulcro.components :as comp]
    [clojure.string :as str]
    [edn-query-language.core :as eql]
    #?(:cljs [goog.dom :as gdom])
    #?(:cljs [goog.object :as gobj])
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.normalize :as fnorm]
    [com.fulcrologic.fulcro.algorithms.application-helpers :as ah])
  #?(:clj (:import (clojure.lang IDeref))))

(defn basis-t
  "Return the current basis time of the app."
  [app]
  (-> app ::runtime-atom deref ::basis-t))

(defn current-state
  "Get the value of the application state database at the current time."
  [app-or-component]
  (let [app (comp/any->app app-or-component)]
    (-> app ::state-atom deref)))

(defn tick!
  "Move the basis-t forward one tick. For internal use and internal algorithms."
  [app]
  (swap! (::runtime-atom app) update ::basis-t inc))

(defn render!
  "Render the application immediately.  Prefer `schedule-render!`, which will ensure no more than 60fps.

  `force-root?` - boolean.  When true disables all optimizations and forces a full root re-render."
  ([app]
   (render! app false))
  ([app force-root?]
   (tick! app)
   (let [{:keys [::runtime-atom ::state-atom]} app
         render! (ah/app-algorithm app :optimized-render!)]
     (binding [fdn/*denormalize-time* (basis-t app)
               comp/*query-state*     @state-atom]
       (render! app force-root?))
     (swap! runtime-atom assoc ::last-rendered-state @state-atom))))

(defn schedule-render!
  "Schedule a render on the next animation frame."
  ([app]
   (schedule-render! app false))
  ([app force-root?]
   #?(:clj  (render! app force-root?)
      :cljs (let [r #(render! app force-root?)]
              (if (not (exists? js/requestAnimationFrame))
                (sched/defer r 16)
                (js/requestAnimationFrame r))))))

(defn- default-tx!
  "Default (Fulcro-2 compatible) transaction submission."
  ([app tx]
   (default-tx! app tx {:optimistic? true}))
  ([{:keys [::runtime-atom] :as app} tx options]
   (txn/schedule-activation! app)
   (let [node (txn/tx-node tx options)
         ref  (get options :ref)]
     (swap! runtime-atom (fn [s] (cond-> (update s ::txn/submission-queue (fnil conj []) node)
                                   ref (update ::components-to-refresh (fnil conj []) ref))))
     (::txn/id node))))

(defn- default-load-error? [{:keys [status-code body] :as result}] (not= 200 status-code))

(defn- default-global-query-transform [query]
  (let [kw-namespace (fn [k] (and (keyword? k) (namespace k)))]
    (util/elide-query-nodes query (fn [k]
                                    (when-let [ns (some-> k kw-namespace)]
                                      (and
                                        (string? ns)
                                        (or
                                          ;; TASK: (= k fs/form-key)
                                          ;; TASK: other "built-ins" to remove?
                                          (= "ui" ns)
                                          (str/ends-with? ns ".ui")
                                          (str/starts-with? ns "ui.")
                                          (str/includes? ns ".ui."))))))))

(defn fulcro-app
  ([] (fulcro-app {}))
  ([{:keys [props-middleware
            render-middleware
            remotes]}]
   {::state-atom   (atom {})
    ::algorithms   {:algorithm/tx!                    default-tx!
                    :algorithm/optimized-render!      ident-optimized/render!
                    :algorithm/render!                render!
                    :algorithm/load-error?            default-load-error?
                    :algorithm/merge*                 merge/merge*
                    :algorithm/global-query-transform default-global-query-transform
                    :algorithm/index-component!       indexing/index-component!
                    :algorithm/drop-component!        indexing/drop-component!
                    :algorithm/props-middleware       props-middleware
                    :algorithm/render-middleware      render-middleware
                    :algorithm/schedule-render!       schedule-render!}
    ::runtime-atom (atom
                     {::app-root                        nil
                      ::mount-node                      nil
                      ::root-class                      nil
                      ::root-factory                    nil
                      ::basis-t                         1
                      ::last-rendered-state             {}
                      ::remotes                         (or remotes
                                                          {:remote {:transmit! (fn [send]
                                                                                 (log/fatal "Remote requested, but no remote defined."))}})
                      ::indexes                         {:ident->components {}}
                      ::mutate                          mut/mutate
                      ::txn/activation-scheduled?       false
                      ::txn/queue-processing-scheduled? false
                      ::txn/sends-scheduled?            false
                      ::txn/submission-queue            []
                      ::txn/active-queue                []
                      ::txn/send-queues                 {}})}))

(defn fulcro-app? [x] (and (map? x) (contains? x ::state-atom) (contains? x ::runtime-atom)))

(defn mounted? [{:keys [::runtime-atom]}]
  (-> runtime-atom deref ::app-root boolean))

(defn mount! [app root node]
  #?(:cljs
     (if (mounted? app)
       (schedule-render! app)
       (let [dom-node     (gdom/getElement node)
             root-factory (comp/factory root)
             root-query   (log/spy :info (comp/get-query root))
             initial-tree (comp/get-initial-state root)
             initial-db   (-> (fnorm/tree->db root-query initial-tree true)
                            (merge/merge-alternate-union-elements root))]
         (reset! (::state-atom app) initial-db)
         (swap! (::runtime-atom app) assoc
           ::mount-node dom-node
           ::root-factory root-factory
           ::root-class root)
         (binding [comp/*app* app]
           (let [app-root (js/ReactDOM.render (root-factory initial-tree) dom-node)]
             (swap! (::runtime-atom app) assoc
               ::app-root app-root)))))))

(defn app-root [app] (-> app ::runtime-atom deref ::app-root))
