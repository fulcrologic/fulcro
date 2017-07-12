(ns cards.component-local-state-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [recipes.component-local-state-client :as client]
    [fulcro.client.cards :refer [fulcro-app]]
    [om.dom :as dom]))

(dc/defcard-doc
  "# Component Local State

  Sometimes you need to use component-local state to avoid the overhead in running an Om query to feed props. An example
  of this is when handing mouse interactions like drag.

  The source of the component in the demo looks like this:"
  (dc/mkdn-pprint-source client/Child))

(dc/defcard local-state
  "# Component Local State

  The component receives mouse move events to show a hover box. To make this move in real-time we use component
  local state. Clicking to set the box, or resize the container are real Om transactions, and will actually cause
  a refresh from application state to update the rendering.

  The application state is shown live under the application so you can see the difference.
  "
  (fulcro-app client/Root)
  {}
  {:inspect-data true})
