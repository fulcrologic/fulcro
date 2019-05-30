(ns com.fulcrologic.fulcro.specs
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.algorithms.misc :as futil]
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn])
  #?(:clj
     (:import (clojure.lang Atom))))

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
(s/def ::app/runtime-atom futil/atom?)
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

