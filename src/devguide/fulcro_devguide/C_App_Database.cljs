(ns fulcro-devguide.C-App-Database
  (:require-macros
    [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [cljs.reader :as r]))

(defcard-doc
  "
  # App database

  In this section we'll discuss the database format used by Fulcro for client state.
  First, we'll describe the problem, and then show how Fulcro's approach to storing app state solves it.

  ## The problem

  Any non-trivial UI needs data. Many non-trivial UIs need a lot of data. React UIs need data to
  be in a tree-like form (parents pass properties down to children). When you combine this
  with ClojureScript and immutable data structures you end up with some interesting challenges (and
  lovely surprising benefits).

  The most important one is this: What do we do when we want to show the same information in
  two different UI components (e.g. a Table and a Chart of some performance statistics)?

  If you structure your data as a tree (for UI benefit), then you have to duplicate the
  data across the tree. Note that now what you have is really a graph.

  <img src=\"svg/ui-tree-dependency-graph.svg\"></img>

  You might think this is OK (structural sharing and pointers make this
  nearly free) until you consider what happens next: time passes. The data needs to update.

  In a mutable world, you'd just update the data in-place, and the pointers would now point
  to that new state. If you're reading this you've already learned the perils and disadvantages of *that*.

  So, now you have the task of finding all of the instances of that data in the application state and updating
  them to the new data set to produce your new (immutable) application state. This turns a localized
  concern (updating the data for a table) into a global one (what is using *this* bit of state in my
  global application state?).

  ## The solution

  Experienced readers will recognize that the solution is the one we've been using in databases
  for quite a long time: normalization...de-dupe the data!

  In Fulcro's database format we do not place the *real* data in multiple places, but instead
  use a special bit of data that acts like a database foreign key. We call these *idents*.

  Here's how it works:

  1. Create a map. This is your application state.

  2. For any given piece of data that you wish to easily share (or access), invent an identifier for it
  of the form `[keyword id]`, where id can be anything (e.g. keyword, int, etc). The explicit
  requirements are:

  - The first item in the ident vector *must* be a keyword
  - The vector *must* have exactly two items.

  3. Now place the real information into the map as follows:

  ```
  (def app-state { :keyword { id real-information }})
  ```

  Notice that what you've now created is a location to store data that can be trivially accessed via
  `get-in` and the *ident*:

  ```
  (get-in app-state [:keyword id])
  ```

  So, now we can represent our earlier example graph as:

  ```
  { :table [:data/statistics :performance]
    :chart [:data/statistics :performance]
    :data/statistics { :performance { ... actual stats ... }}}
  ```

  Note also that the objects stored this way are also encouraged to use idents to
  reference other state. So you could build a database of people are their partners like this:

  ```
  { :list/people [ [:people/by-id 1] [:people/by-id 2] ... ]
    :people/by-id { 1 { :db/id 1 :person/name \"Joe\" :person/mate [:people/by-id 2]}
                    2 { :db/id 2 :person/name \"Sally\" :person/mate [:people/by-id 1]}}}
  ```

  The top-level key `:list/people` is a made-up keyword for my list of people that
  I'm interested in (e.g. currently on the UI). It points to Joe and Sally.

  <img src=\"svg/app-database-diagram.svg\"/>

  The database table keyed at `:people/by-id` stores the real object, which cross-reference each
  other. Note that this particular graph (as you might expect) has a loop in it (Joe is married
  to Sally who is married to Joe). NOTE: Having loops in your graph is perfectly fine, though if you
  query recursive data in your UI you will have to use computed state (depth) to prevent targeted refresh
  from causing the displayed depth to creep.

  ## Everything in tables

  By now you might have realized that you can put just about everything into this table format.

  For example, if we have multiple different lists of people we might choose to store
  *those* in more of a table format:

  ```
  { :lists/by-category { :friends { :list/id :friends :people [ [:people/by-id 1] [:people/by-id 2] ] }
                         :enemies { :list/id :enemies :people [ [:people/by-id 5] [:people/by-id 9] ] }}
    :people/by-id { 1 { :db/id 1 :person/name \"Joe\" :person/mate [:people/by-id 2]}
                    2 { :db/id 2 :person/name \"Sally\" :person/mate [:people/by-id 1]} ... }}

  ```

  This will work very well.

  ## Some things not in tables?

  If data has a reasonable identity, it should get an ident and go in a table. There are situations where you'll keep
  data in a tree form: specifically, if the data is owned by something, but has no good identity of its own, then it
  should just remain nested map data. For example, you might want address information to appear as a nested data
  structure, but perhaps the persisted identity of it is that of its owner:

  ```
  { :people/by-id { 1 { :db/id 1 :person/address {:address/street ...}}}}
  ```

  If you were to read/write this data you'd always do it in the context of the person. This is a good example of where
  there is no good reason to normalize nested state. As a side-note: if you nest the UI and build queries for address
  (which you can still do, even if it isn't normalized), you should do your transactions from the parent that has an ident.
  This allows the UI refresh to do a more efficient job (normalized components can be refreshed more quickly in isolation).

  In general, keep your application state normalized. The graph nature fixes duplication issues,
  allows you to easily generate a tree for rendering (as we'll see soon), allows for very flexible UI refactoring,
  makes mutation code simple *and* easy, allows you to embed any part of your application in a devcard, and makes it easy
  for you to easily find and examine the state of any entity in the database.

  ## Top-level state

  Fulcro also has great support for accessing data at the root of the graph (see [Using Links](#!/fulcro_devguide.D_Queries)).
  So if you have data that you'd like to access from arbitrary locations just store it under a (namespaced) keyword at the top level:

  ```
  { :current/user [:person/by-id 2] }
  ```

  ## Custom Types?

  You may be wondering if you can use your own data types in the database. The answer is
  a conditional 'yes'. Opaque values (those you don't intend to process with the query
  engine) can be any type. In the next chapter on Queries you'll learn how to
  query the database via property queries and joins. Anything that is meant to
  act as a simple property and is *not* treated as an entity that can be
  queried itself can be of any type.

  NOTE: If you want the support viewer to work, the database must be serializable. The transit protocol uses EDN, and is
  extensible, so you can very easily add custom marshalling to support your types. All of the built-in clojure types + timestamps
  are already present.

  Any *entity* (entry in the database that is meant to be a first-class citizen
  of the graph database and processed with queries) can only be a plain map (technically they could also be records, but
  normalization/denormalization will cause them to morph to maps and not maintain their type).

  So, if you had some table named `:table` it would be keyed by the entity IDs, and
  the entity *itself* will be a plain map; however, any of the properties (like
  `:stats`) can be any type you care them to be:

  ```
  { :table { 3 { :id 3 :value 22 :stats some-object-of-any-type}}}
  ```

  ## Manual graph building? Do I have to build that by hand?

  No. You do not need to build normalized graph databases. Fulcro can do that for you.
  Fulcro also provides a protocol called `InitialAppState`. This can be attached to each component
  in the same manner as `IQuery`.  The benefit is that you don't have to think as much about
  normalization or building a map of initial app state.  You simply define it with regards to the
  component, and compose over child components. This greatly complements the query concepts and improves
  local reasonsing with regard to initial state and component composition.
  We will discuss this more in the coming chapters.

  You should definitely do the [database exercises](#!/fulcro_devguide.C_App_Database_Exercises).
  ")

