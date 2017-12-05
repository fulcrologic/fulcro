(ns cards.card-ui
  (:require
    devcards.core
    cards.A-Introduction
    cards.UI-router-as-editor-with-type-selection
    cards.UI-router-as-list-with-item-editor
    cards.autocomplete
    cards.cascading-dropdowns
    cards.component-local-state
    cards.component-localized-css
    cards.declarative-mutation-refresh
    cards.dynamic-i18n-locale-cards
    cards.dynamic-routing-with-code-splitting
    cards.initial-app-state
    cards.legacy-load-indicators
    cards.loading-indicators
    cards.loading-data-basics
    cards.loading-in-response-to-UI-routing
    cards.paginating-large-lists-from-server
    cards.parallel-vs-sequential-loading
    cards.parent-child-ownership-relations
    cards.server-SQL-graph-queries
    cards.server-error-handling
    cards.server-query-security
    cards.server-return-values-as-data-driven-mutation-joins
    cards.server-return-values-manually-merging
    [fulcro.client.logging :as log]))

(log/set-level :debug)

(devcards.core/start-devcard-ui!)
