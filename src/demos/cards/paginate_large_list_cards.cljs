(ns cards.paginate-large-list-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [recipes.paginate-large-lists-client :as client]
    [fulcro.client.cards :refer [fulcro-app]]
    [fulcro.client.dom :as dom]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
    [fulcro.client.core :as fc]
    [fulcro.client.primitives :as om]))

(dc/defcard paginate-list-card
  "
  # Paginating Large Lists

  Note: This is a full-stack example. Make sure you're running the server and are serving this page from it.

  This demo is showing a (dynamically generated) list of items. The server can generate any number of them, so you
  can page ahead as many times as you like. Each page is dynamically loaded if and only if the browser does not already
  have it. The server has an intentional delay so you can be sure to see when the loads happen. The initial startup
  loads the first page automatically (reload this page if you missed that).

  The demo also ensure you cannot run out of browser memory by removing items and pages that are more than 4 steps away
  from your current position. You can demostrate this by moving ahead by more than 4 pages, then page back 5. You should
  see a reload of that early page when you go back to it.
  "
  (fulcro-app client/Root :started-callback
    (fn [{:keys [reconciler]}]
      (om/transact! reconciler `[(client/goto-page {:page-number 1})])))
  {}
  {:inspect-data false})

(dc/defcard-doc
  "# Explanation

  The UI of this example is a great example of how a complex application behavior remains very very simple at the UI
  layer with Fulcro.

  We represent the list items as you might expect:
  "
  (dc/mkdn-pprint-source client/ListItem)
  "
  We then generate a component to represent a page of them. This allows us to associate the items on a page with
  a particular component, which makes tracking the page number and items on that page much simpler:
  "
  (dc/mkdn-pprint-source client/ListPage)
  "
  Next we build a component to control which page we're on called `LargeList`. This component does nothing more than
  show the current page, and transact mutations that ask for the specific page. Not that we could easily add a control
  to jump to any page, since the mutation itself is `goto-page`.
  "
  (dc/mkdn-pprint-source client/LargeList)
  (dc/mkdn-pprint-source client/Root)
  "## The Mutation

  So you can infer that all of the complexity of this application is hidden behind a single mutation: `goto-page`. This
  mutation is a complete abstraction to the UI, and the UI designer would need to very little about it.

  We've decided that this mutation will:

  - Ensure the given page exists in app state (with its page number)
  - Check to see if the page has items
    - If not: it will trigger a server-side query for those items
  - Update the `LargeList`'s current page to point to the correct page
  - Garbage collect pages/items in the app database that are 5 or more pages away from the current position.

  The mutation itself looks like this:

  ```
  (m/defmutation goto-page [{:keys [page-number]}]
    (action [{:keys [state] :as env}]
      (load-if-missing env page-number)
      (swap! state (fn [s]
                     (-> s
                       (init-page page-number)
                       (set-current-page page-number)
                       (gc-distant-pages page-number)))))
    (remote [{:keys [state] :as env}]
      (when (not (page-exists? @state page-number))
        (df/remote-load env)))) ; trigger load queue processing
  ```

  Let's break this down.

  ### The Action

  The `load-if-missing` function is composed of the following bits:
  "
  (dc/mkdn-pprint-source client/page-exists?)
  (dc/mkdn-pprint-source client/load-if-missing)
  "
  and you can see that it just detects if the page is missing its items. If the items are missing, it uses the
  `load-action` function (which adds a load entry to the load queue from within a mutation). The parameters
  to `load-action` are nearly identical to `load`, except for the first argument (which for `load-action` is
  the env of the mutation). Note that when adding an item to the load queue from within a mutation, the mutation
  should also trigger the load queue processing via the remote side of the mutation via `remote-load` (as shown above).

  The call to `swap!` lets us compose together the remaining actions: `init-page`, `set-current-page`,
  and `gc-distant-pages`. Those should really not be surprising at all, since they are just local manipulations of
  the database. The source for them is below:
  "
  (dc/mkdn-pprint-source client/init-page)
  (dc/mkdn-pprint-source client/set-current-page)
  (dc/mkdn-pprint-source client/clear-item)
  (dc/mkdn-pprint-source client/clear-page)
  (dc/mkdn-pprint-source client/gc-distant-pages)
  "## The Server-Side Code

  The server in this example is trivial. It is just a query that generates items on the fly with a slight delay:

  ```
  (defmethod api/server-read :paginate/items [env k {:keys [start end]}]
    (Thread/sleep 100)
    (when (> 1000 (- end start)) ; ensure the server doesn't die if the client does something like use NaN for end
      {:value (vec (for [id (range start end)]
                     {:item/id id}))}))
  ```
  ")


