(ns cards.dynamic-routing-cards
  (:require [om.dom :as dom]
            [recipes.dynamic-ui-routing :as dur]
            [devcards.core :as dc :refer-macros [defcard]]
            [fulcro.client.cards :refer-macros [fulcro-app]]))

(defcard router-demo
  "# Router Demo

  Background colors are used to show where the screens shown are different, and possibly nested."
  (fulcro-app dur/Root :started-callback dur/application-loaded)
  {}
  {:inspect-data true})
