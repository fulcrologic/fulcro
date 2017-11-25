(ns cards.background-load-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [recipes.background-loads-client :as bg]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [fulcro.client.dom :as dom]))

(dc/defcard-doc
  "# Background Loads

  This is a full-stack example. See the Introduction for how to run the demo server.

  Note that all of the examples share the same server, but the server code is isolated for each using
  namespacing of the queries and mutations.

  This is a simple application that shows off the difference between regular loads and those marked parallel.

  Normally, Fulcro runs separate event-based loads in sequence, ensuring that your reasoning can be synchronous;
  however, for loads that might take some time to complete, and for which you can guarantee order of
  completion doesn't matter, you can specify an option on load (`:parallel true`) that allows them to proceed in parallel.

  The buttons in the card below come from this UI component:
  "
  (dc/mkdn-pprint-source bg/Child)
  "
  and you can see how they trigger the same load.")

(defcard-fulcro background-loads
  "# Background Loads

   The server has a built-in delay of 5 seconds. Pressing the sequential buttons on the three (in any order) will take
   at least 15 seconds to complete from the time you click the first one (since each will run after the other is complete).
   If you rapidly click the parallel buttons, then the loads will not be sequenced, and you will see them complete in roughly
   5 seconds overall (from the time you click the last one).
  "
  bg/Root)