(ns untangled-devguide.M30-Advanced-Mutation
  (:require-macros [untangled-devguide.tutmacros :refer [untangled-app]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [untangled-devguide.A-Quick-Tour :as tour]
            [untangled.client.core :as uc]
            [untangled.client.logging :as log]
            [untangled.client.data-fetch :as df]))

(defui SubQuery
  static om/Ident
  (ident [this props] [:sub/by-id (:id props)])
  static om/IQuery
  (query [this] [:id :data]))

(defui TopQuery
  static om/Ident
  (ident [this props] [:top/by-id (:id props)])
  static om/IQuery
  (query [this] [:id {:subs (om/get-query SubQuery)}]))

(defcard-doc
  "
  # Advanced Mutation Topics

  All of your local UI operations will go through the mechanisms described in the section on Mutations. This section
  covers a number of additional issues that arise in more advanced situations. Almost all of these circumstances arise
  from needing to modify your application database *outside* of the normal `om/transact!` mechanism at the UI layer.

  The first note is that `om/transact!` can be used on the Om reconciler. If you've saved your Untangled Application
  in a top-level atom (as recommended in the templates), then you can run a transaction \"globally\" like this:

  ```
  (om/transact! @app ...)
  ```

  This should generally be used in cases where there is an abstract operation (e.g. you want `setTimeout` to update
  a js/Date to the current time and have the screen refresh). Using `(om/transact! (:reconciler @app) '[(update-time) :current-time])` is
  much clearer and in the spirit of the framework than any other low-level data tweaking. That could also be done in
  the context of a component to prevent an overall root re-render, though you'd want to be careful to use both sides of
  the component lifecycle to install *and* remove a timer that triggers such an update.

  ### Leveraging `om/tree->db`

  In some cases you will have obtained some data (or perhaps invented it) and you need to integrate that data into the
  database. If the data matches your UI structure (as a tree) and you have proper `Ident` declarations on those components
  then you can simply transform the data into the correct shape via Om Next's `tree->db` function.

  Unfortunately, you would then need to follow that transform by a sequence of operations on app state to merge those
  various bits.

  The function also requires a query in order to do normalization (split the tree into tables).

  IMPORTANT: The general interaction with the world requires integration of external data (often in a tree format) with
  your app database (normalized graph of maps/vectors). As a result, you almost always want an Om-managed query when
  integrating data, so that the result is normalized. This is also why mutations don't have return values: there is
  no query to use to merge such a result (in stock Om Next you 
  could override merge to try to address this, but we feel that 
  creates a concentration of cross-cutting concerns in `merge` with 
  little practical benefit).

  ### Creating Components *Just* For Their Queries

  If your UI doesn't have a query that is convenient for sending to the server (or for working on tree data like this),
  then it is considered perfectly fine to generate components just for their queries (no render). This is often quite
  useful, especially in the context of pre-loading data that gets placed on the UI in a completely different form (e.g. the
  UI queries don't match what you'd like to ask the server).

  "
  (dc/mkdn-pprint-source TopQuery)
  (dc/mkdn-pprint-source SubQuery)
  "

  Once we have a query, we can use `tree->db` to convert some result to the desired format.
  ")

(defcard sample-of-tree->db
         "In this card, we're using the above TopQuery query to convert the data in the displayed map `:from` into
         `:result`. The specific operation used here is:

         ```
         (om/tree->db TopQuery (:from @state) true)
         ```

         "
         (fn [state _]
           (dom/div nil
             (dom/button #js {:onClick (fn []
                                         (let [result (om/tree->db TopQuery (:from @state) true)]
                                           (swap! state assoc :result result)))} "Run tree->db")
             (dom/button #js {:onClick (fn []
                                         (swap! state dissoc :result))} "Reset")))
         {:from {:id :top-1 :subs [{:id :sub-1 :data 1} {:id :sub-2 :data 2}]}}
         {:inspect-data true})

(defcard-doc
  "

  of course, you can see that you're still going to need to merge the database table contents into your main app state
  and carefully integrate the other bits as well.

  *IMPORTANT*: The *`Ident`* part of the component's is the magic here. This is why you *need* component queries for
  this work work right. The `ident` functions are used to determine the table locations and idents to place into
  the normalized database!

  ### Using `om/merge!`

  Om Next includes a function that takes care of the rest of these bits for you. It uses the Om Next reconciler (which
  as we mentioned earlier can be taked from the Untangled App). The arguments are similar to `tree->db`:

  ```
  (om/merge! (:reconciler @app) ROOT-data ROOT-query)
  ```

  The same things apply as `tree->db` (idents especially), however, the result of the transform will make it's way into
  the app state (which is owned by the reconciler).

  *IMPORTANT*: The biggest challenge with using this function is that it requires the data and query to be structured
  from the ROOT of the database! That is sometimes perfectly fine, but our next section talks about a helper that
  might be more appropriate.

")

(defonce timer-id (atom 0))
(defonce merge-sample-app (atom nil))

(defn add-counter [app counter]
  (uc/merge-state! app tour/Counter counter
                   :append [:panels/by-kw :counter :counters]))

(defui ^:once Root
  static om/IQuery
  (query [this] [{:panel (om/get-query tour/CounterPanel)}])
  Object
  (render [this]
    (let [{:keys [panel]} (om/props this)]
      (dom/div #js {:style #js {:border "1px solid black"}}
        (dom/button #js {:onClick #(add-counter @merge-sample-app {:counter/id 4 :counter/n 22})} "Simulate Data Import")
        "Counters:"
        (tour/ui-counter-panel panel)))))

(defcard-doc
  "
  ### Using `untangled.client.core/merge-state!`

  There is a common special case that comes up often: You want to merge something that is in the context of some particular UI component.

  Think of this case as: I have some data for a given component (which MUST have an Ident). I want to merge onto that
  component's entry in the table, but I want to make sure the tree of data in my source data also gets normalized
  properly.

  This helper function also integrates the functionality of `integrate-ident!`, and can often serve as a total one-stop
  shop for merging data that is coming from some external source. Since this is an Untangled function, it can work directly on
  the app, but also accepts a reconciler.

  In the card below we're using part of our Counter application from earlier. The button simulates some external source of data that
  we'd like to merge in. In this case, let's assume we are wanting to merge some newly arrived counter:

  ```
  { :counter/id 5 :counter/n 66 }
  ```

  We'll want to:

  - Add the counter to the counter's table (which is not even present because we have none in our initial app state)
  - Add the ident of the counter to the panel's counters tracking

  We can do this with:
  "
  (dc/mkdn-pprint-source add-counter))

(defcard sample-of-counter-app-with-merge-state
         "The button in the UI calls `(add-counter app {:counter/id 4 :counter/n 22})`"
         (dc/dom-node
           (fn [state-atom node]
             (reset! merge-sample-app (uc/new-untangled-client :initial-state state-atom :networking (tour/map->MockNetwork {})))
             (reset! merge-sample-app (uc/mount @merge-sample-app Root node))))
         {:panel         [:panels/by-kw :counter]
          :panels/by-kw  {:counter {:counters []}}
          :counter/by-id {}}
         {:inspect-data true})

