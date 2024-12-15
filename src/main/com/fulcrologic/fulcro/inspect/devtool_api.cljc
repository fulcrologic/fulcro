(ns com.fulcrologic.fulcro.inspect.devtool-api
  "These are declarations of the remote mutations that are callable on the Fulcro Inspect Dev tool. Internal use.

   They are declared in the Fulcro project so the internals can be connected to the Inspect Devtool without having
   the dev tool be a release build requirement."
  (:require
    [com.fulcrologic.devtools.common.resolvers :refer [remote-mutations]]))

(remote-mutations
  app-started
  db-changed
  send-started
  send-finished
  send-failed
  optimistic-action
  statechart-event)
