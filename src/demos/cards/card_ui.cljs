(ns cards.card-ui
  (:require
    devcards.core
    cards.A-Introduction
    cards.UI_router_as_editor_with_type_selection
    cards.UI_router_as_list_with_item_editor
    cards.autocomplete
    cards.component_local_state
    cards.component_localized_css
    cards.declarative_mutation_refresh
    cards.dynamic_i18n_locale_cards
    cards.dynamic_routing_with_code_splitting
    cards.initial_app_state
    cards.legacy_loading_indicators
    cards.loading_data_basics
    cards.loading_in_response_to_UI_routing
    cards.paginating_large_lists_from_server
    cards.parallel_vs_sequential_loading
    cards.parent_child_ownership_relations
    cards.server_SQL_graph_queries
    cards.server_error_handling
    cards.server_query_security
    cards.server_return_values_as_data_driven_mutation_joins
    cards.server_return_values_manually_merging
    [fulcro.client.logging :as log]))

(log/set-level :debug)

(devcards.core/start-devcard-ui!)
