(ns cards.card-ui
  (:require
    devcards.core
    cards.A-Introduction
    [fulcro.client.logging :as log]))

(log/set-level :debug)

(devcards.core/start-devcard-ui!)

