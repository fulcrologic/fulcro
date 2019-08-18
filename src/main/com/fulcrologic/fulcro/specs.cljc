(ns com.fulcrologic.fulcro.specs
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as futil]
    [edn-query-language.core :as eql]))

;; ================================================================================
;; Transaction Specs
;; ================================================================================

(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/id uuid?)
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/idx int?)
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/created inst?)
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/started inst?)
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/finished inst?)
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/tx vector?)
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/abort-id any?)
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/options (s/keys
                                                                    :opt [:com.fulcrologic.fulcro.algorithms.tx-processing/abort-id]
                                                                    :opt-un [:com.fulcrologic.fulcro.algorithms.tx-processing/abort-id]))
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/started? set?)
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/complete? set?)
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/results (s/map-of keyword? any?))
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/progress (s/map-of keyword? any?))
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/dispatch map?) ; a tree is also a node
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/ast :edn-query-language.ast/node)
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/original-ast-node :com.fulcrologic.fulcro.algorithms.tx-processing/ast)
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/tx-element (s/keys
                                                                       :req [:com.fulcrologic.fulcro.algorithms.tx-processing/idx
                                                                             :com.fulcrologic.fulcro.algorithms.tx-processing/original-ast-node
                                                                             :com.fulcrologic.fulcro.algorithms.tx-processing/started?
                                                                             :com.fulcrologic.fulcro.algorithms.tx-processing/complete?
                                                                             :com.fulcrologic.fulcro.algorithms.tx-processing/results
                                                                             :com.fulcrologic.fulcro.algorithms.tx-processing/dispatch]
                                                                       :opt [:com.fulcrologic.fulcro.algorithms.tx-processing/progress]))
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/elements (s/coll-of :com.fulcrologic.fulcro.algorithms.tx-processing/tx-element :kind vector?))
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/tx-node
  (s/keys :req [:com.fulcrologic.fulcro.algorithms.tx-processing/id
                :com.fulcrologic.fulcro.algorithms.tx-processing/created
                :com.fulcrologic.fulcro.algorithms.tx-processing/options
                :com.fulcrologic.fulcro.algorithms.tx-processing/tx
                :com.fulcrologic.fulcro.algorithms.tx-processing/elements]
    :opt [:com.fulcrologic.fulcro.algorithms.tx-processing/started
          :com.fulcrologic.fulcro.algorithms.tx-processing/finished]))

(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/result-handler fn?)
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/update-handler fn?)
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/active? boolean?)
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/parallel? boolean?)

(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/send-node (s/keys
                                                                      :req [:com.fulcrologic.fulcro.algorithms.tx-processing/id
                                                                            :com.fulcrologic.fulcro.algorithms.tx-processing/idx
                                                                            :com.fulcrologic.fulcro.algorithms.tx-processing/ast
                                                                            :com.fulcrologic.fulcro.algorithms.tx-processing/result-handler
                                                                            :com.fulcrologic.fulcro.algorithms.tx-processing/update-handler
                                                                            :com.fulcrologic.fulcro.algorithms.tx-processing/active?]
                                                                      :opt [:com.fulcrologic.fulcro.algorithms.tx-processing/options]))

(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/submission-queue (s/coll-of :com.fulcrologic.fulcro.algorithms.tx-processing/tx-node :kind vector?))
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/active-queue (s/coll-of :com.fulcrologic.fulcro.algorithms.tx-processing/tx-node :kind vector?))
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/send-queue (s/coll-of :com.fulcrologic.fulcro.algorithms.tx-processing/send-node :kind vector?))
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/send-queues (s/map-of :com.fulcrologic.fulcro.application/remote-name :com.fulcrologic.fulcro.algorithms.tx-processing/send-queue))

(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/activation-scheduled? boolean?)
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/sends-scheduled? boolean?)
(s/def :com.fulcrologic.fulcro.algorithms.tx-processing/queue-processing-scheduled? boolean?)

;; ================================================================================
;; Application Specs
;; ================================================================================
(s/def :com.fulcrologic.fulcro.application/state-atom futil/atom?)
(s/def :com.fulcrologic.fulcro.application/app-root (s/nilable any?))
(s/def :com.fulcrologic.fulcro.application/runtime-atom futil/atom?)

;; indexing-related
(s/def :com.fulcrologic.fulcro.application/ident->components (s/map-of eql/ident? set?))
(s/def :com.fulcrologic.fulcro.application/prop->classes (s/map-of (s/or :keyword keyword? :ident eql/ident?) set?))
(s/def :com.fulcrologic.fulcro.application/class->components (s/map-of keyword? set?))
(s/def :com.fulcrologic.fulcro.application/idents-in-joins (s/coll-of eql/ident? :kind set?))
(s/def :com.fulcrologic.fulcro.application/indexes (s/keys :opt-un [:com.fulcrologic.fulcro.application/ident->components
                                                                      :com.fulcrologic.fulcro.application/keyword->components
                                                                      :com.fulcrologic.fulcro.application/idents-in-joins
                                                                      :com.fulcrologic.fulcro.application/class->components]))

(s/def :com.fulcrologic.fulcro.application/remote-name keyword?)
(s/def :com.fulcrologic.fulcro.application/remote-names (s/coll-of keyword? :kind set?))
(s/def :com.fulcrologic.fulcro.application/remotes (s/map-of :com.fulcrologic.fulcro.application/remote-name map?))
(s/def :com.fulcrologic.fulcro.application/active-remotes (s/coll-of :com.fulcrologic.fulcro.application/remote-name :kind set?))
(s/def :com.fulcrologic.fulcro.application/basis-t pos-int?)
(s/def :com.fulcrologic.fulcro.application/last-rendered-state map?)
(s/def :com.fulcrologic.fulcro.application/runtime-state (s/keys :req [:com.fulcrologic.fulcro.application/app-root
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
(s/def :com.fulcrologic.fulcro.algorithm/tx! fn?)
(s/def :com.fulcrologic.fulcro.algorithm/optimized-render! fn?)
(s/def :com.fulcrologic.fulcro.algorithm/render! fn?)
(s/def :com.fulcrologic.fulcro.algorithm/merge* fn?)
(s/def :com.fulcrologic.fulcro.algorithm/remote-error? fn?)
(s/def :com.fulcrologic.fulcro.algorithm/schedule-render! fn?)
(s/def :com.fulcrologic.fulcro.algorithm/global-eql-transform (s/nilable fn?))
(s/def :com.fulcrologic.fulcro.algorithm/shared-fn (s/nilable fn?))
(s/def :com.fulcrologic.fulcro.algorithm/global-error-action (s/nilable fn?))
(s/def :com.fulcrologic.fulcro.algorithm/default-result-action! fn?)
(s/def :com.fulcrologic.fulcro.algorithm/index-root! fn?)
(s/def :com.fulcrologic.fulcro.algorithm/index-component! fn?)
(s/def :com.fulcrologic.fulcro.algorithm/drop-component! fn?)
(s/def :com.fulcrologic.fulcro.algorithm/props-middleware (s/nilable fn?))
(s/def :com.fulcrologic.fulcro.algorithm/render-middleware (s/nilable fn?))

(s/def :com.fulcrologic.fulcro.application/algorithms
  (s/keys
    :req [:com.fulcrologic.fulcro.algorithm/default-result-action!
          :com.fulcrologic.fulcro.algorithm/drop-component!
          :com.fulcrologic.fulcro.algorithm/index-component!
          :com.fulcrologic.fulcro.algorithm/index-root!
          :com.fulcrologic.fulcro.algorithm/merge*
          :com.fulcrologic.fulcro.algorithm/optimized-render!
          :com.fulcrologic.fulcro.algorithm/remote-error?
          :com.fulcrologic.fulcro.algorithm/render!
          :com.fulcrologic.fulcro.algorithm/schedule-render!
          :com.fulcrologic.fulcro.algorithm/tx!]
    :opt [:com.fulcrologic.fulcro.algorithm/global-eql-transform
          :com.fulcrologic.fulcro.algorithm/global-error-action
          :com.fulcrologic.fulcro.algorithm/props-middleware
          :com.fulcrologic.fulcro.algorithm/render-middleware
          :com.fulcrologic.fulcro.algorithm/shared-fn]))

(s/def :com.fulcrologic.fulcro.application/app (s/keys :req
                                                   [:com.fulcrologic.fulcro.application/state-atom
                                                    :com.fulcrologic.fulcro.application/algorithms
                                                    :com.fulcrologic.fulcro.application/runtime-atom]))

