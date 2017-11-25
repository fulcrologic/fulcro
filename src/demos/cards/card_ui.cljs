(ns cards.card-ui
  (:require
    devcards.core
    cards.A-Introduction
    cards.autocomplete-cards
    cards.background-load-cards
    cards.component-local-state-cards
    cards.colocated-css-cards
    cards.dynamic-i18n-locale-cards
    cards.declarative-mutation-refresh
    cards.code-splitting-with-dynamic-routing-cards
    cards.defrouter-for-type-selection-cards
    cards.defrouter-list-and-editor-cards
    cards.error-handling-cards
    cards.initial-state-cards
    cards.fulcro-legacy-loading-indicator-cards
    cards.lists-cards
    cards.load-samples-cards
    cards.mutation-return-value-manual-merge
    cards.mutation-join-cards
    cards.paginate-large-list-cards
    cards.server-query-security-cards
    cards.sql-graph-query-cards
    cards.tabbed-interface-cards
    cards.websocket-cards
    [fulcro.client.logging :as log]))

(log/set-level :debug)

(devcards.core/start-devcard-ui!)

