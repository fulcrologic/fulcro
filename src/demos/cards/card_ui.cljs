(ns cards.card-ui
  (:require
    devcards.core
    cards.A-Introduction
    cards.autocomplete-cards
    cards.background-load-cards
    cards.component-local-state-cards
    cards.convenience-macro-cards
    cards.colocated-css-cards
    cards.dynamic-i18n-locale-cards
    cards.dynamic-routing-cards
    cards.defrouter-for-type-selection-cards
    cards.defrouter-list-and-editor-cards
    cards.error-handling-cards
    cards.initial-state-cards
    cards.lazy-loading-indicators-cards
    cards.lists-cards
    cards.load-samples-cards
    cards.mutation-return-value-cards
    cards.paginate-large-list-cards
    cards.server-query-security-cards
    cards.sql-graph-query-cards
    cards.tabbed-interface-cards
    cards.websocket-cards
    [fulcro.client.logging :as log]))

(log/set-level :debug)

(devcards.core/start-devcard-ui!)

