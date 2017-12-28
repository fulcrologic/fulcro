(ns fulcro-tutorial.M30-Advanced-Mutation
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro-tutorial.A-Quick-Tour :as tour]
            [fulcro.client :as fc]
            [fulcro.client.logging :as log]
            [fulcro.client.data-fetch :as df]))

(defsc SubQuery [t p]
  {:ident [:sub/by-id :id]
   :query [:id :data]})

(defsc TopQuery [t p]
  {:ident [:top/by-id :id]
   :query [:id {:subs (prim/get-query SubQuery)}]})

(defcard-doc
  "
  # Advanced Mutation Topics

  All of your local UI operations will go through the mechanisms described in the section on Mutations. This section
  covers a number of additional issues that arise in more advanced situations. Almost all of these circumstances arise
  from needing to modify your application database *outside* of the normal `prim/transact!` mechanism at the UI layer.

  The first note is that `prim/transact!` can be used on the reconciler. If you've saved your Fulcro Application
  in a top-level atom (as recommended in the templates), then you can run a transaction \"globally\" like this:

  ```
  (prim/transact! (:reconciler @app) ...)
  ```

  This should generally be used in cases where there is an abstract operation (e.g. you want `setTimeout` to update
  a js/Date to the current time and have the screen refresh). Using `(prim/transact! (:reconciler @app) '[(update-time) :current-time])` is
  much clearer and in the spirit of the framework than any other low-level data tweaking. That could also be done in
  the context of a component to prevent an overall root re-render, though you'd want to be careful to use both sides of
  the component lifecycle to install *and* remove a timer that triggers such an update.

  ## Can I Swap on the State Atom?

  Yes. There is a watch on Fulcro's state atom. Doing so will cause a refresh from the root of your UI.

  `(let [{:keys [reconciler]} @app
         state-atom (prim/app-state reconciler)]
    (swap! state-atom ...))`

  ## Leveraging `prim/tree->db`

  In some cases you will have obtained some data (or perhaps invented it) and you need to integrate that data into the
  database. If the data matches your UI structure (as a tree) and you have proper `Ident` declarations on those components
  then you can simply transform the data into the correct shape via the `tree->db` function using a component's
  query.

  Unfortunately, you would then need to follow that transform by a sequence of operations on app state to merge those
  various bits.

  The function also requires a query in order to do normalization (split the tree into tables).

  IMPORTANT: The general interaction with the world requires integration of external data (often in a tree format) with
  your app database (normalized graph of maps/vectors). As a result, you almost always want a component-based query when
  integrating data so that the result is normalized.

  ##  Using `integrate-ident`

  When in a mutation you very often need to place an ident in various spots in your graph database. The helper
  function `fulcro.client/integrate-ident` can by used from within mutations to help you do this. It accepts
  any number of named parameters that specify operations to do with a given ident:

  ```
  (swap! state
    (fn [s]
      (-> s
         (do-some-op)
         (integrate-ident the-ident
           :append [:table id :field]
           :prepend [:other id :field]))))
  ```

  This function checks for the existence of the given ident in the target list, and will refuse to add it if it is
  already there. The `:replace` option can be used on a to-one or to-many relation. When replacing on a to-many, you
  use an index in the target path (e.g. `[:table id :field 2]` would replace the third element in a to-many `:field`)

  ## Creating Components *Just* For Their Queries

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
  (prim/tree->db TopQuery (:from @state) true)
  ```
  "
  (fn [state _]
    (dom/div nil
      (dom/button #js {:onClick (fn []
                                  (let [result (prim/tree->db TopQuery (:from @state) true)]
                                    (swap! state assoc :result result)))} "Run tree->db")
      (dom/button #js {:onClick (fn []
                                  (swap! state dissoc :result))} "Reset")))
  {:from {:id :top-1 :subs [{:id :sub-1 :data 1} {:id :sub-2 :data 2}]}}
  {:inspect-data true})

(defcard-doc
  "

  of course, you can see that you're still going to need to merge the database table contents into your main app state
  and carefully integrate the other bits as well.

  *IMPORTANT*: The *`ident`* part of the component is the magic here. This is why you *need* component queries for
  this work work right. The `ident` functions are used to determine the table locations and idents to place into
  the normalized database!

  ## Using `prim/merge!`

  Fulcro includes a function that takes care of the rest of these bits for you. It requires the reconciler (which
  as we mentioned earlier can be obtained from the Fulcro App). The arguments are similar to `tree->db`:

  ```
  (prim/merge! (:reconciler @app) ROOT-data ROOT-query)
  ```

  The same things apply as `tree->db` (idents especially), however, the result of the transform will make it's way into
  the app state (which is owned by the reconciler).

  *IMPORTANT*: The biggest challenge with using this function is that it requires the data and query to be structured
  from the ROOT of the database! That is sometimes perfectly fine, but our next section talks about a helper that
  might be easier to use.")

(defonce timer-id (atom 0))
(declare sample-of-counter-app-with-merge-component-fulcro-app)

(defn add-counter [app counter]
  (prim/merge-component! (:reconciler app) tour/Counter counter
    :append [:panels/by-kw :counter :counters]))

(defsc Root [this {:keys [panel]}]
  {:query [{:panel (prim/get-query tour/CounterPanel)}]}
  (dom/div #js {:style #js {:border "1px solid black"}}
    (dom/button #js {:onClick #(add-counter @sample-of-counter-app-with-merge-component-fulcro-app {:counter/id 4 :counter/n 22})} "Simulate Data Import")
    "Counters:"
    (tour/ui-counter-panel panel)))

(defcard-doc
  "
  ## Using `prim/merge-component!`

  There is a common special case that comes up often: You want to merge something that is in the context of some particular UI component.

  ```
  (prim/merge-component! app ComponentClass ComponentData)
  ```

  Think of this case as: I have some data for a given component (which MUST have an ident). I want to merge into that
  component's entry in a table, but I want to make sure the recursive tree of data also gets normalized
  properly.

  `merge-component!` also integrates the functionality of `integrate-ident!` to pepper the ident of the merged entity throughout
  your app database, and can often serve as a total one-stop
  shop for merging data that is coming from some external source.

  This first argument can be an application or reconciler.

  In the card below we're using part of our Counter application from earlier. The button simulates some external source of data that
  we'd like to merge in. In this case, let's assume we are wanting to merge some newly arrived counter entity:

  ```
  { :counter/id 5 :counter/n 66 }
  ```

  We'll want to:

  - Add the counter to the counter's table (which is not even present because we have none in our initial app state)
  - Add the ident of the counter to the UI panel so it's UI shows up

  We can do this with:
  "
  (dc/mkdn-pprint-source add-counter)
  "given the following components:"
  (dc/mkdn-pprint-source tour/CounterPanel)
  (dc/mkdn-pprint-source tour/Root))

(defcard-fulcro sample-of-counter-app-with-merge-component
  "The button in the UI calls `(add-counter app {:counter/id 4 :counter/n 22})`"
  Root
  {:panel         [:panels/by-kw :counter]
   :panels/by-kw  {:counter {:counters []}}
   :counter/by-id {}}
  {:inspect-data true
   :fulcro       {:networking (tour/map->MockNetwork {})}})

