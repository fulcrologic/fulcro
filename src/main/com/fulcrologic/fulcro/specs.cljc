(ns com.fulcrologic.fulcro.specs
  (:require
    [ghostwheel.core :as gw]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.algorithms.misc :as futil]
    [edn-query-language.core :as eql]))

;; ================================================================================
;; Transaction Specs
;; ================================================================================

(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/id uuid?)
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/idx int?)
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/created inst?)
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/started inst?)
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/finished inst?)
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/tx vector?)
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/options map?)
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/started? set?)
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/complete? set?)
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/results (s/map-of keyword? any?))
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/progress (s/map-of keyword? any?))
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/dispatch map?)
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/ast :edn-query-language.ast/node) ; a tree is also a node
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/original-ast-node :com.fulcrologic.fulcro.algorithms.tx-processing/ast)
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/tx-element (s/keys
                                                                       :req [:com.fulcrologic.fulcro.algorithms.tx-processing/idx
                                                                             :com.fulcrologic.fulcro.algorithms.tx-processing/original-ast-node
                                                                             :com.fulcrologic.fulcro.algorithms.tx-processing/started?
                                                                             :com.fulcrologic.fulcro.algorithms.tx-processing/complete?
                                                                             :com.fulcrologic.fulcro.algorithms.tx-processing/results
                                                                             :com.fulcrologic.fulcro.algorithms.tx-processing/dispatch]
                                                                       :opt [:com.fulcrologic.fulcro.algorithms.tx-processing/progress]))
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/elements (s/coll-of :com.fulcrologic.fulcro.algorithms.tx-processing/tx-element :kind vector?))
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/tx-node
  (s/keys :req [:com.fulcrologic.fulcro.algorithms.tx-processing/id
                :com.fulcrologic.fulcro.algorithms.tx-processing/created
                :com.fulcrologic.fulcro.algorithms.tx-processing/options
                :com.fulcrologic.fulcro.algorithms.tx-processing/tx
                :com.fulcrologic.fulcro.algorithms.tx-processing/elements]
    :opt [:com.fulcrologic.fulcro.algorithms.tx-processing/started
          :com.fulcrologic.fulcro.algorithms.tx-processing/finished]))

(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/result-handler fn?)
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/update-handler fn?)
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/active? boolean?)
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/parallel? boolean?)

(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/send-node (s/keys
                                                                      :req [:com.fulcrologic.fulcro.algorithms.tx-processing/id
                                                                            :com.fulcrologic.fulcro.algorithms.tx-processing/idx
                                                                            :com.fulcrologic.fulcro.algorithms.tx-processing/ast
                                                                            :com.fulcrologic.fulcro.algorithms.tx-processing/result-handler
                                                                            :com.fulcrologic.fulcro.algorithms.tx-processing/update-handler
                                                                            :com.fulcrologic.fulcro.algorithms.tx-processing/active?]
                                                                      :opt [:com.fulcrologic.fulcro.algorithms.tx-processing/parallel?]))

(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/submission-queue (s/coll-of :com.fulcrologic.fulcro.algorithms.tx-processing/tx-node :kind vector?))
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/active-queue (s/coll-of :com.fulcrologic.fulcro.algorithms.tx-processing/tx-node :kind vector?))
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/send-queue (s/coll-of :com.fulcrologic.fulcro.algorithms.tx-processing/send-node :kind vector?))
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/send-queues (s/map-of :com.fulcrologic.fulcro.application/remote-name :com.fulcrologic.fulcro.algorithms.tx-processing/send-queue))

(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/activation-scheduled? boolean?)
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/sends-scheduled? boolean?)
(gw/>def :com.fulcrologic.fulcro.algorithms.tx-processing/queue-processing-scheduled? boolean?)

;; ================================================================================
;; Application Specs
;; ================================================================================
(gw/>def :com.fulcrologic.fulcro.application/state-atom futil/atom?)
(gw/>def :com.fulcrologic.fulcro.application/app-root (s/nilable any?))
(gw/>def :com.fulcrologic.fulcro.application/indexes (s/keys :req-un [:com.fulcrologic.fulcro.application/ident->components]))
(gw/>def :com.fulcrologic.fulcro.application/ident->components (s/map-of eql/ident? set?))
(gw/>def :com.fulcrologic.fulcro.application/keyword->components (s/map-of keyword? set?))
(gw/>def :com.fulcrologic.fulcro.application/link-joins->components (s/map-of eql/ident? set?))
(gw/>def :com.fulcrologic.fulcro.application/remote-name keyword?)
(gw/>def :com.fulcrologic.fulcro.application/remote-names (s/coll-of keyword? :kind set?))
(gw/>def :com.fulcrologic.fulcro.application/remotes (s/map-of :com.fulcrologic.fulcro.application/remote-name map?))
(gw/>def :com.fulcrologic.fulcro.application/basis-t pos-int?)
(gw/>def :com.fulcrologic.fulcro.application/last-rendered-state map?)
(gw/>def :com.fulcrologic.fulcro.application/runtime-state (s/keys :req [:com.fulcrologic.fulcro.application/app-root
                                                                         :com.fulcrologic.fulcro.application/indexes
                                                                         :com.fulcrologic.fulcro.application/remotes
                                                                         :com.fulcrologic.fulcro.application/basis-t
                                                                         :com.fulcrologic.fulcro.application/last-rendered-state
                                                                         :com.fulcrologic.fulcro.algorithms.tx-processing/activation-scheduled?
                                                                         :com.fulcrologic.fulcro.algorithms.tx-processing/queue-processing-scheduled?
                                                                         :com.fulcrologic.fulcro.algorithms.tx-processing/sends-scheduled?
                                                                         :com.fulcrologic.fulcro.algorithms.tx-processing/submission-queue
                                                                         :com.fulcrologic.fulcro.algorithms.tx-processing/active-queue
                                                                         :com.fulcrologic.fulcro.algorithms.tx-processing/send-queues]))
(gw/>def :com.fulcrologic.fulcro.application/runtime-atom futil/atom?)
(gw/>def :algorithm/tx! fn?)
(gw/>def :algorithm/optimized-render! fn?)
(gw/>def :algorithm/render! fn?)
(gw/>def :algorithm/merge* fn?)
(gw/>def :algorithm/remote-error? fn?)
(gw/>def :algorithm/index-component! fn?)
(gw/>def :algorithm/drop-component! fn?)
(gw/>def :algorithm/schedule-render! fn?)
(gw/>def :algorithm/global-eql-transform fn?)
(gw/>def :com.fulcrologic.fulcro.application/algorithms (s/keys :req [:algorithm/tx!
                                                                      :algorithm/optimized-render!
                                                                      :algorithm/render!
                                                                      :algorithm/merge*
                                                                      :algorithm/remote-error?
                                                                      :algorithm/global-eql-transform
                                                                      :algorithm/index-component!
                                                                      :algorithm/drop-component!
                                                                      :algorithm/schedule-render!]))

(gw/>def :com.fulcrologic.fulcro.application/app (s/keys :req
                                                   [:com.fulcrologic.fulcro.application/state-atom
                                                    :com.fulcrologic.fulcro.application/algorithms
                                                    :com.fulcrologic.fulcro.application/runtime-atom]))

