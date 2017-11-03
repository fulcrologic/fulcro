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

  In this section we'll discuss the database format used by Fulcro for client state. Fulcro
  has chosen to not allow pluggable database formats. This allows the framework to do a lot of
  heavy lifting for you, and so far has been very acceptable for the production applications
  we've built with it.

  First, we'll describe the problem, and then show how Om's approach to storing app state solves it.

  ## The problem

  Any non-trivial UI needs data. Many non-trivial UIs need a lot of data. React UIs need data to
  be in a tree-like form (parents pass properties down to children). When you combine these facts
  with ClojureScript and immutable data structures you end up with some interesting challenges.

  The most important one is this: What do we do when we want to show the same information in
  two different UI components (e.g. a Table and a Chart of some performance statistics)?

  If you structure your data as a tree (for UI benefit), then you have to duplicate the
  data across the tree. Note that now what you have is really a graph.

  <img src=\"svg/ui-tree-dependency-graph.svg\"></img>

  You might think this is OK (structural sharing and pointers make this
  nearly free) until you consider what happens next: time passes. The data needs to update.

  In a mutable world, you'd just update the data in-place, and the pointers would now point
  to that new state. If you're reading this you've already learned the perils and disadvantages of *that*.

  So, now you have the lovely task of finding all of that data in the application state and updating
  it to the new data set to produce your new (immutable) application state. This turns a localized
  concern (updating the data for a table) into a global one (what is using *this* bit of state in my
  global application state?).

  ## The solution

  Experienced readers will recognize that the solution is the one we've been using in databases
  for quite a long time: normalization...de-dupe the data!

  In Fulcro's database format we do not place the *real* data in multiple places, but instead
  use a special bit of data that acts like a database foreign key. Om calls these *idents*.

  Here's how it works:

  Create a map. This is your application state.

  For any given piece of data that you wish to easily share (or access), invent an identifier for it
  of the form `[keyword id]`, where id can be anything (e.g. keyword, int, etc). The explicit
  requirements are:

  - The first item in the ident vector *must* be a keyword
  - The vector *must* have exactly two items.

  Now place the real information into the map as follows:

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
  to Sally who is married to Joe ...).

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

  In practice, some things in your application are really singletons (such as the details of
  the current user). So, in practice it makes perfect sense to just store those things
  in the top level of your overall application state.

  One criteria you might consider before placing data into a tree is changing it over time (in
  value or location). If you nest some bit of state way down in a tree and need to update
  that state, you'll end up writing code that is tied to that tree structure. For example:
  `(update-in state [:root :list :wrapper-widget :friends] conj new-friend)`. Not only
  is this painful to write, it ties a local UI concern into your state management code
  and starts to look like a controller from MVC. It also means that if you write a different
  (e.g. mobile) UI, you won't easily re-use that bit of code.

  Om has great support for true singletons in the database (and queries, see [Using Links](#!/fulcro_devguide.D_Queries)).
  So if you have this kind of data just store it under a (namespaced) keyword at the top level:

  ```
  { :current/user {:user/name ...} }
  ```

  In general, keep your application state flat. The graph nature fixes duplication issues,
  allows you to easily generate a tree for rendering (as we'll see soon),
  and the flat structure makes mutation code easy to write and maintain.

  ## Custom Types?

  You may be wondering if you can use your own data types in the database. The answer is
  a conditional 'yes'. Opaque values (those you don't intend to process with the query
  engine) can be any type. In the next chapter on Queries you'll learn how to
  query the database via property queries and joins. Anything that is meant to
  act as a simple property and is *not* treated as an entity that can be
  queried itself can be of any type.
  Any *entity* (entry in the database that is meant to be a first-class citizen
  of the graph database and processed with queries) can only be a plain map.

  So, if you had some table named `:table` it would be keyed by the entity IDs, and
  the entity *itself* will be a plain map; however, any of the properties (like
  `:stats`) can be any type you care them to be:

  ```
  { :table { 3 { :id 3 :value 22 :stats some-object-of-any-type}}}
  ```

  ## Bleh, manual graph building... Do I have to build that by hand?

  No. You do not need to build normalized graph databases. Fulcro can do that for you.
  Fulcro also provides a protocol called `InitialAppState`. This can be attached to each component
  in the same manner as `fulcro.client.primitives/IQuery`.  The benefit is that you don't have to think as much about
  normalization or building a map of initial app state.  You simply define it with regards to the
  component, and compose over child components. This greatly complements the query concepts and improves
  local reasonsing with regard to initial state and component composition.
  We will discuss this more in the coming chapters.

  You should definitely do the [database exercises](#!/fulcro_devguide.C_App_Database_Exercises).
  ")

