(ns cards.dynamic-routing-cards
  (:require [om.dom :as dom]
            [recipes.dynamic-ui-routing :as dur]
            [devcards.core :as dc :refer-macros [defcard]]
            [fulcro.client.cards :refer-macros [fulcro-app]]))

(defcard router-demo
  "# Dyanmic Router Demo

  NOTE: Not working yet. Seems like a bug in new code splitting load support.
  "
  (fulcro-app dur/Root :started-callback dur/application-loaded)
  {}
  {:inspect-data true})
