(ns cards.cascading-dropdown-cards
  (:require
    [devcards.core :as dc :refer-macros [defcard-doc]]
    [fulcro.ui.bootstrap3 :as bs]
    [recipes.cascading-dropdown-client :as client]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [om.dom :as dom]))

(defcard-doc
  "# Cascading Dropdowns

  A common UI desire is to have dropdowns that cascade. I.e. a dropdown populates in response to a selection in
  an earlier dropdown, like Make/Model for cars.

  This can be done quite easily. This is a full-stack example that requires you run the demo server. See the Intro
  card for instructions.

  The basic implementation is as follows:

  1. Define dropdowns that can display the items
  2. Don't initialize the extra ones with items
  3. When the first one is given a selection, load the next one

  A couple of simple implementation details are needed:

  1. We're using bootstrap dropdowns, and we need to know where they normalize their data. Looking at the data inspector
  for the card makes this easy to see. For example, we can see that items are stored in the
  `:bootstrap.dropdown/by-id` table, in the `:fulcro.ui.bootstrap3/items` column.
  2. The IDs of the dropdowns (which we generate)

  On the server, we define the query handler as follows (with a 2-second delay so we can play with load visualization):

  ```
  (defquery-root :models
    (value [env {:keys [make]}]
      (Thread/sleep 2000)
      (case make
        :ford [(bs/dropdown-item :escort \"Escort\")
               (bs/dropdown-item :F-150 \"F-150\")]
        :honda [(bs/dropdown-item :civic \"Civic\")
                (bs/dropdown-item :accort \"Accord\")])))
  ```

  The complete UI is then just:
  "
  (dc/mkdn-pprint-source client/Root)
  "

  and we define a mutation for showing a \"Loading...\" item in the dropdown that is loading as:

  ```
  (defmutation show-list-loading
    \"Change the items of the dropdown with the given ID to a single item that indicates Loading...\"
    [{:keys [id]}]
    (action [{:keys [state]}]
      (swap! state assoc-in
        [:bootstrap.dropdown/by-id id :fulcro.ui.bootstrap3/items]
        [(assoc (bs/dropdown-item :loading \"Loading...\") :fulcro.ui.bootstrap3/disabled? true)])))
  ```

  The main action is in the `onSelect` of the first dropdown, which just issues the transact to set the loading
  visualization, followed by the remote load.
  ")

(defcard-fulcro cascading-dropdown-card
  client/Root
  {}
  {:inspect-data true})
