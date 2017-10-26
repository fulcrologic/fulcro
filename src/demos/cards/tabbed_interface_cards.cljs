(ns cards.tabbed-interface-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [recipes.tabbed-interface-client :as client]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [fulcro.client.dom :as dom]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
    [fulcro.client.core :as fc]
    [fulcro.client.primitives :as om]))

(defcard-fulcro tabbed-card
  "
  # Tabbed Interface with Pane Content Dynamic Loading

  Note: This is a full-stack example. Make sure you're running the server and are serving this page from it.
  "
  client/Root)

(dc/defcard-doc
  "
  Tabbed interfaces typically use a UI Router (which can be further integrated into HTML5 routing as a routing tree). See
  [this YouTube video](https://youtu.be/j-_itpXEo6w?list=PLVi9lDx-4C_T_gsmBQ_2gztvk6h_Usw6R) for more details.

  This example not only shows the basic construction of an interface that allows content (and query) to be switched, it
  also demonstrates how one goes about triggerng loads of data that some screen might need.

  If you look at the source for the root component you'll see two buttons with transactions on their click handlers.
  "
  (dc/mkdn-pprint-source client/Root)
  "
  The first is simple enough: run a mutation that chooses which tab to show. The routing library includes a helper function
  for building that, so the mutation just looks like this:

  ```
  (m/defmutation choose-tab [{:keys [tab]}]
    (action [{:keys [state]}] (swap! state r/set-route :ui-router [tab :tab])))
  ```

  The transaction to go to the settings tab is more interesting. It switches tabs but also runs another mutation to
  load data needed for that screen. The intention is to just load it if it is missing. That mutation looks like this:

  ```
  (defn missing-tab? [state tab] (empty? (-> @state :settings :tab :settings)))

  (m/defmutation lazy-load-tab [{:keys [tab]}]
    (action [{:keys [state] :as env}]
      (when (missing-tab? state tab)
        (df/load-action state :all-settings SomeSetting
          {:target  [:settings :tab :settings]
           :refresh [:settings]})
        ))
    (remote [{:keys [state] :as env}]
      (when (missing-tab? state tab) (df/remote-load env))))
  ```

  Fairly standard fare at this point: Look at the database to see if it has what you want, and if not trigger a load
  with `df/load-action` (on the action side) and `df/remote-load` on the remote.
  ")

