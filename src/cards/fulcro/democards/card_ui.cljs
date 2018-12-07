(ns fulcro.democards.card-ui
  (:require
    [clojure.spec.test.alpha :as st]
    fulcro.democards.dom-cards
    fulcro.democards.localized-dom-cards
    fulcro.democards.load-cards
    fulcro.democards.pessimistic-transaction-cards
    fulcro.democards.i18n-cards
    fulcro.democards.manual-tests-of-dynamic-queries
    fulcro.democards.react-refs
    fulcro.democards.react16-cards
    fulcro.democards.routing-cards
    fulcro.democards.root-form-refresh
    fulcro.democards.parent-state-refresh
    devcards.core))

;(st/instrument)

;; Use this for adv opt build:
;(devcards.core/start-devcard-ui!) 
