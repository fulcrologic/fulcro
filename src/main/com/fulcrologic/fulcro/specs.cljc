(ns com.fulcrologic.fulcro.specs
  (:require
    [ghostwheel.core :refer [>fdef =>]]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.algorithms.misc :as futil]
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn])
  #?(:clj
     (:import (clojure.lang Atom))))

(defn atom? [a] (instance? Atom a))

(defn atom-of [content-spec]
  (fn [x]
    (and
      (atom? x)
      ;; TODO: figure out how to nest spec
      )))

;; ================================================================================
;; Transaction Specs
;; ================================================================================

(s/def ::txn/id uuid?)
(s/def ::txn/idx int?)
(s/def ::txn/created inst?)
(s/def ::txn/started inst?)
(s/def ::txn/finished inst?)
(s/def ::txn/tx vector?)
(s/def ::txn/options map?)
(s/def ::txn/started? set?)
(s/def ::txn/complete? set?)
(s/def ::txn/results (s/map-of keyword? any?))
(s/def ::txn/progress (s/map-of keyword? any?))
(s/def ::txn/dispatch map?)
(s/def ::txn/ast :edn-query-language.ast/node)              ; a tree is also a node
(s/def ::txn/original-ast-node ::txn/ast)
(s/def ::txn/tx-element (s/keys
                          :req [::txn/idx ::txn/original-ast-node ::txn/started? ::txn/complete? ::txn/results ::txn/dispatch]
                          :opt [::txn/progress]))
(s/def ::txn/elements (s/coll-of ::txn/tx-element :kind vector?))
(s/def ::txn/tx-node (s/keys :req [::txn/id ::txn/created ::txn/options ::txn/tx ::txn/elements]
                       :opt [::txn/started ::txn/finished]))

(s/def ::txn/result-handler fn?)
(s/def ::txn/update-handler fn?)
(s/def ::txn/active? boolean?)
(s/def ::txn/parallel? boolean?)

(s/def ::txn/send-node (s/keys
                         :req [::txn/id ::txn/idx ::txn/ast ::txn/result-handler ::txn/update-handler ::txn/active?]
                         :opt [::txn/parallel?]))

(s/def ::txn/submission-queue (s/coll-of ::txn/tx-node :kind vector?))
(s/def ::txn/active-queue (s/coll-of ::txn/tx-node :kind vector?))
(s/def ::txn/send-queue (s/coll-of ::txn/send-node :kind vector?))
(s/def ::txn/send-queues (s/map-of ::app/remote-name ::txn/send-queue))

(s/def ::txn/activation-scheduled? boolean?)
(s/def ::txn/sends-scheduled? boolean?)
(s/def ::txn/queue-processing-scheduled? boolean?)

(>fdef txn/extract-parallel [sends] [(s/coll-of ::txn/send-node :kind vector?) => (s/cat :p ::txn/send-queue :rest ::txn/send-queue)])
(>fdef txn/every-ast? [ast-node-or-tree test] [::txn/ast fn? => boolean?])
(>fdef txn/mutation-ast? [ast-node-or-tree] [::txn/ast => boolean?])
(>fdef txn/sort-queue-writes-before-reads [send-queue] [::txn/send-queue => ::txn/send-queue])
(>fdef txn/top-keys [{:keys [type key children] :as ast}] [::txn/ast => (s/coll-of :edn-query-language.ast/key)])
(>fdef txn/combine-sends [app remote-name send-queue] [:com.fulcrologic.fulcro.application/app :com.fulcrologic.fulcro.application/remote-name ::txn/send-queue => (s/keys :opt [::txn/send-node] :req [::txn/send-queue])])
(>fdef txn/net-send! [{:com.fulcrologic.fulcro.application/keys [remotes] :as app} send-node remote-name] [:com.fulcrologic.fulcro.application/app ::txn/send-node :com.fulcrologic.fulcro.application/remote-name => any?])
(>fdef txn/process-send-queues! [{:com.fulcrologic.fulcro.application/keys [remotes runtime-atom] :as app}] [:com.fulcrologic.fulcro.application/app => ::txn/send-queues])
(>fdef txn/defer [f tm] [fn? int? => any?])
(>fdef txn/tx-node
  ([tx] [::txn/tx => ::txn/tx-node])
  ([tx options] [::txn/tx ::txn/options => ::txn/tx-node]))
(>fdef txn/build-env
  ([app tx-node addl] [:com.fulcrologic.fulcro.application/app ::txn/tx-node map? => map?])
  ([app tx-node] [:com.fulcrologic.fulcro.application/app ::txn/tx-node => map?]))
(>fdef app/default-tx!
  ([app tx] [:com.fulcrologic.fulcro.application/app ::txn/tx => ::txn/id])
  ([app tx options] [:com.fulcrologic.fulcro.application/app ::txn/tx ::txn/options => ::txn/id]))
(>fdef txn/dispatch-elements [tx-node env dispatch-fn] [::txn/tx-node map? fn? => ::txn/tx-node])
(>fdef txn/schedule!
  ([app scheduled-key action tm] [:com.fulcrologic.fulcro.application/app keyword? fn? int? => any?])
  ([app scheduled-key action] [:com.fulcrologic.fulcro.application/app keyword? fn? => any?]))
(>fdef txn/activate-submissions! [{:com.fulcrologic.fulcro.application/keys [runtime-atom] :as app}] [:com.fulcrologic.fulcro.application/app => any?])
(>fdef txn/schedule-activation!
  ([app tm] [:com.fulcrologic.fulcro.application/app int? => any?])
  ([app] [:com.fulcrologic.fulcro.application/app => any?]))
(>fdef txn/schedule-queue-processing!
  ([app tm] [:com.fulcrologic.fulcro.application/app int? => any?])
  ([app] [:com.fulcrologic.fulcro.application/app => any?]))
(>fdef txn/schedule-sends!
  ([app tm] [:com.fulcrologic.fulcro.application/app int? => any?])
  ([app] [:com.fulcrologic.fulcro.application/app => any?]))
(>fdef txn/advance-actions! [app node] [:com.fulcrologic.fulcro.application/app ::txn/tx-node => ::txn/tx-node])
(>fdef txn/run-actions! [app node] [:com.fulcrologic.fulcro.application/app ::txn/tx-node => ::txn/tx-node])
(>fdef txn/fully-complete? [app tx-node] [:com.fulcrologic.fulcro.application/app ::txn/tx-node => boolean?])
(>fdef txn/remove-send! [app remote txn-id ele-idx] [:com.fulcrologic.fulcro.application/app :com.fulcrologic.fulcro.application/remote-name ::txn/id ::txn/idx => any?])
(>fdef txn/record-result!
  ([app txn-id ele-idx remote result result-key] [:com.fulcrologic.fulcro.application/app ::txn/id int? keyword? any? keyword? => any?])
  ([app txn-id ele-idx remote result] [:com.fulcrologic.fulcro.application/app ::txn/id int? keyword? any? => any?]))
(>fdef txn/add-send! [app tx-node ele-idx remote] [:com.fulcrologic.fulcro.application/app ::txn/tx-node ::txn/idx :com.fulcrologic.fulcro.application/remote-name => ::txn/send-node])
(>fdef txn/queue-element-sends! [app tx-node tx-element] [:com.fulcrologic.fulcro.application/app ::txn/tx-node ::txn/tx-element => ::txn/tx-node])
(>fdef txn/idle-node? [tx-node] [::txn/tx-node => boolean?])
(>fdef txn/element-with-work [remote-names element] [:com.fulcrologic.fulcro.application/remote-names ::txn/tx-element => (s/nilable ::txn/tx-element)])
(>fdef txn/queue-next-send! [app tx-node] [:com.fulcrologic.fulcro.application/app ::txn/tx-node => ::txn/tx-node])
(>fdef txn/queue-sends! [app tx-node] [:com.fulcrologic.fulcro.application/app ::txn/tx-node => ::txn/tx-node])
(>fdef txn/dispatch-result! [app tx-node tx-element remote] [:com.fulcrologic.fulcro.application/app ::txn/tx-node ::txn/tx-element keyword? => ::txn/tx-element])
(>fdef txn/distribute-element-results! [app tx-node tx-element] [:com.fulcrologic.fulcro.application/app ::txn/tx-node ::txn/tx-element => ::txn/tx-element])
(>fdef txn/distribute-results! [app tx-node] [:com.fulcrologic.fulcro.application/app ::txn/tx-node => ::txn/tx-node])
(>fdef txn/update-progress! [app tx-node] [:com.fulcrologic.fulcro.application/app ::txn/tx-node => ::txn/tx-node])
(>fdef txn/process-tx-node! [app tx-node] [:com.fulcrologic.fulcro.application/app ::txn/tx-node => (s/nilable ::txn/tx-node)])
(>fdef txn/process-queue! [app] [:com.fulcrologic.fulcro.application/app => any?])

;; ================================================================================
;; Application Specs
;; ================================================================================
(s/def ::app/state-atom futil/atom?)
(s/def ::app/app-root (s/nilable any?))
(s/def ::app/indexes (s/keys :req-un [::app/ident->components]))
(s/def ::app/ident->components (s/map-of eql/ident? set?))
(s/def ::app/keyword->components (s/map-of keyword? set?))
(s/def ::app/link-joins->components (s/map-of eql/ident? set?))
(s/def ::app/remote-name keyword?)
(s/def ::app/remote-names (s/coll-of keyword? :kind set?))
(s/def ::app/remotes (s/map-of ::app/remote-name map?))
(s/def ::app/basis-t pos-int?)
(s/def ::app/last-rendered-state map?)
(s/def ::app/runtime-state (s/keys :req [::app/app-root
                                         ::app/indexes
                                         ::app/remotes
                                         ::app/basis-t
                                         ::app/last-rendered-state
                                         ::txn/activation-scheduled?
                                         ::txn/queue-processing-scheduled?
                                         ::txn/sends-scheduled?
                                         ::txn/submission-queue
                                         ::txn/active-queue
                                         ::txn/send-queues]))
(s/def ::app/runtime-atom (atom-of ::app/runtime-state))
(s/def :algorithm/tx! fn?)
(s/def :algorithm/optimized-render! fn?)
(s/def :algorithm/render! fn?)
(s/def :algorithm/merge* fn?)
(s/def :algorithm/load-error? fn?)
(s/def :algorithm/index-component! fn?)
(s/def :algorithm/drop-component! fn?)
(s/def :algorithm/schedule-render! fn?)
(s/def :algorithm/global-query-transform fn?)
(s/def ::app/algorithms (s/keys :req [:algorithm/tx!
                                      :algorithm/optimized-render!
                                      :algorithm/render!
                                      :algorithm/merge*
                                      :algorithm/load-error?
                                      :algorithm/global-query-transform
                                      :algorithm/index-component!
                                      :algorithm/drop-component!
                                      :algorithm/schedule-render!]))

(s/def ::app/app (s/keys :req
                   [::app/state-atom
                    ::app/algorithms
                    ::app/runtime-atom]))

