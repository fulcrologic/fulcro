(ns fulcro-devguide.H11-Network-Activity-Indicators
  (:require-macros [cljs.test :refer [is]])
  (:require
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.mutations :as m]
            [fulcro.client :as fc]))

(defcard-doc
  "
  # Network Activity Indicators

  Rendering is completely up to you, but Fulcro handles the networking (technically this is pluggable, but Fulcro still initiates
  the interactions). That means that you're going to need some help when it comes to showing the user that something is happening on the network.

  The first and easiest thing to use is a global activity marker that is automatically maintained at the root node of
  your client database.

  ## Global network activity marker

  Fulcro will automatically maintain a global network activity marker at the top level of the app state under the
  keyword `:ui/loading-data`. This key will have a `true` value when there are network requests awaiting a response from
  the server, and will have a `false` value when there are no network requests in process.

  You can access this marker from any component that composes to root by including a link in your component's query:

  ```
  (defsc Item ... )
  (def ui-item (prim/factory Item {:keyfn :id}))

  (defsc List [this {:keys [title items ui/loading-data]}]
    {:query [:id :title {:items (prim/get-query Item)} [:ui/loading-data '_]]}
    ...
    (if (and loading-data (empty? items))
      (dom/div nil \"Loading...\")
      (dom/div nil
        (dom/h1 nil title)
        (map ui-item items))))
  ```

  Because the global loading marker is at the top level of the application state, do not use the keyword as a follow-on
  read to mutations because it may unnecessarily trigger a re-render of the entire application.

  ## Mutation Activity

  Mutations can be passed off silently to the server. You may choose to block the UI if you have reason to believe there
  will be a problem, but there is usually no other reason to prevent the user from just continuing to use your application
  while the server processes the mutation. Thus, only the global activity marker is available for mutations.

  ## Tracking Specific Loads

  Loads are a different story. It is very often the case that you might have a number of loads running to populate different
  parts of your UI all at once. In this case it is quite useful to have some kind of load-specific marker that you
  can use to show that activity.

  In Fulcro 1.0 this was done as follows:

  - The target of each load was replaced by a *load marker* until the load completed
  - You can detect these load markers and show an alternate UI while they are loading
  - When the data arrived the load marker was replaced by it.

  This is still supported (and is still currently the default); however, it is deprecated because it was found to be less than ideal:

  1. It caused the old data to disappear. There was noplace else to put the targeted load marker except *over* the old data.
  This caused flicker and workarounds (such as mis-targeting the data and using post-mutations to put it in place at the end)
  2. The load markers are rather large. Looking at your component's app state during a load is kind of ugly.
  3. The load markers could not be queried from elsewhere, meaning activity indicators had to be local to the loaded data.
  4. Worst: you had to add a special query property to the component representing the thing being loaded, or the load marker
  wasn't even available. This bit of magic was hard to understand.

  In Fulcro 2.0, it is recommended that you use a keyword (of your own invention) for the `:marker` option instead. This
  has the following behavior:

  1. Load markers are placed in a top-level table (the var fulcro.client.data-fetch/marker-table holds the table name),
  using your keyword as their ID. They are normalized!
  2. You can therefore explicitly query for them using an ident join

  This solves all of the prior system's weaknesses.

  ### Working with Load Markers

  The steps are rather simple: Include the `:marker` parameter with load, and issue a query for the load marker on
  the marker table. The table name for markers is stored in the data-fetch namespace as `df/marker-table`.

  ```
  (defsc SomeComponent [this props]
    {:query [:data :prop2 :other [df/marker-table :marker-id]]} ; an ident in queries pulls in an entire entity
    (let [marker (get props [df/marker-table :marker-id])]
      ...)))

  ...

  (df/load this :key Item {:marker :marker-id})
  ```

  The data fetch load marker will be missing if no loading is in progress. You can use the following functions
  to detect what state the load is in:

  - `(df/ready? m)` - Returns true if the item is queued, but not yet active on the network
  - `(df/loading? m)` - Returns true if the item is active on the network

  The marker will disappear from the table when network activity completes.

  The rendering is up to you, but that is really all there is to it.

  ## Marker IDs for Items That Have Many Instances

  The most confusing part of normalized load markers is that the IDs are keywords, but you may need a marker for a specific
  entity. Say you have a list of people, and you'd like to show an activity marker on the one you're refreshing. You
  have many on the screen, so you can't just use a simple keyword as the marker ID or they might all show a loading
  indicator when only one is updating.

  In this case you will need to generate a marker ID based on the entity ID, and then use a link query to pull the
  entire load marker table to see what is loading.

  For example, you might define the marker IDs as `(keyword \"person-load-marker\" (str person-id))`. Your person
  component could then find its load marker like this:

  ```
  (defn person-markerid [id] (keyword \"person-load-marker\" (str id)))

  (defsc Person [this {:keys [db/id person/name]}]
    {:query [:db/id :person/name [df/marker-table '_]]
     :ident [:person/by-id :db/id]}
    (let [marker-id   (person-markerid id)
          all-markers (get props df/marker-table)
          marker      (get all-markers marker-id)]
        ...))
  ```

  the load might look something like this:

  ```
  (df/load this [:person/by-id 42] Person {:marker (person-markerid 42)})
  ```

  See `src/demos` in the Fulcro github repository for a running example.

  ## What's Next?

  Now that the user can see what is going on, let's talk about more advanced topics. What if we need to
  [return a value from a mutation](#!/fulcro_devguide.H12_Mutation_Return_Values).
")
