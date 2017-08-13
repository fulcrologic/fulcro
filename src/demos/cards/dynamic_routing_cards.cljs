(ns cards.dynamic-routing-cards
  (:require [om.dom :as dom]
            [recipes.dynamic-ui-routing :as dur]
            [devcards.core :as dc :refer-macros [defcard]]
            [fulcro.client.cards :refer [defcard-fulcro]]))

(defcard-fulcro router-demo
  "# Dyanmic Router Demo

  NOTE: Not working yet. Seems like a bug in new code splitting load support.
  "
  dur/Root
  {}
  {:inspect-data true
   :fulcro       {:started-callback dur/application-loaded}})
