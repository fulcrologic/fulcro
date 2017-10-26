(ns cards.initial-state-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [recipes.initial-app-state-client :as client]
    [fulcro.client.cards :refer [fulcro-app]]
    [fulcro.client.dom :as dom]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]))


(dc/defcard-doc
  "# Initial State

  Fulcro's initial state support allows you to compose the initial (startup) state using the components themselves.
  This allows you to co-locate the component initial state for local reasoning, and compose children into
  parents so that any component in the app can be easily relocated. If such components also have an ident, any
  mutations need to interact with those components will automatically just work, since you'll be working on
  normalized data!

  The source of the demo components is:
  "
  (dc/mkdn-pprint-source client/Main)
  (dc/mkdn-pprint-source client/Settings)
  (dc/mkdn-pprint-source client/PaneSwitcher)
  (dc/mkdn-pprint-source client/ItemLabel)
  (dc/mkdn-pprint-source client/Foo)
  (dc/mkdn-pprint-source client/Bar)
  (dc/mkdn-pprint-source client/ListItem)
  (dc/mkdn-pprint-source client/Root))

(dc/defcard initial-state
  "
  Note: There are two union queries in this application. Notice how the initial app state manages to find them all even
  though one of them is not in the initial tree of initial state (PaneSwitcher composes in Main, but Settings is
  auto-found and added as well).
  "
  (fulcro-app client/Root)
  {}
  {:inspect-data true})
