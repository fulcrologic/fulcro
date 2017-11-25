(ns cards.component-local-state-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [recipes.component-local-state-client :as client]
    [fulcro.client.cards :refer [fulcro-app]]
    [fulcro.client.dom :as dom]))

(dc/defcard-doc
  "# Component Local State

  Sometimes you need to use component-local state to avoid the overhead in running a query to feed props. An example
  of this is when handing mouse interactions like drag.

  There are actually two ways to change component-local state. One of them defers rendering to the next animation frame,
  but it *also* reconcles the database with the stateful components. This one will not give you as much of a speed boost
  (though it may be enough, since you're not changing the database or recording more UI history).

  The other mechanism completely avoids this, and just asks React for an immeidate forced update.

  - `(set-state! this data)` and `(update-state! this data)` - trigger a reconcile against the database at the next animation frame. Limits frame rate to 60 fps.
  - `(react-set-state! this data)` - trigger a React forceUpdate immediately

  In this example we're using `set-state!`, and you can see it is still plenty fast!

  The source of the component in the demo looks like this:"
  (dc/mkdn-pprint-source client/Child))

(dc/defcard local-state
  "# Component Local State

  The component receives mouse move events to show a hover box. To make this move in real-time we use component
  local state. Clicking to set the box, or resize the container are real transactions, and will actually cause
  a refresh from application state to update the rendering.

  The application state is shown live under the application so you can see the difference.
  "
  (fulcro-app client/Root)
  {}
  {:inspect-data true})
