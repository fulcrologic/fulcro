(ns fulcro.democards.card-ui
  (:require
    [clojure.spec.test.alpha :as st]
    fulcro.democards.dom-cards
    fulcro.democards.localized-dom-cards
    fulcro.democards.load-cards
    fulcro.democards.i18n-alpha-cards
    fulcro.democards.manual-tests-of-dynamic-queries
    fulcro.democards.react-refs
    fulcro.democards.root-form-refresh
    fulcro.democards.parent-state-refresh
    devcards.core))

;(st/instrument)
(devcards.core/start-devcard-ui!)
