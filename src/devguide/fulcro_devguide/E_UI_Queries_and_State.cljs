(ns fulcro-devguide.E-UI-Queries-and-State
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim]
            [fulcro.client.impl.parser :as p]
            [fulcro-devguide.queries.query-editing :as qe]
            [fulcro.client.dom :as dom]
            [cljs.reader :as r]
            [fulcro-devguide.queries.query-demo :as qd]
            [devcards.util.edn-renderer :refer [html-edn]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(defcard-doc
  "
  # UI, queries, and state

  Now that you understand the database format and queries you're ready to join it all together and get
  some things on the screen!

  ## Co-located queries on components

  ### The problem

  So, we've seen great ways to lay out our data into these nice graph databases, and we've seen how
  to query them and even build retrieval code. Two questions remain:

  1. What's the easiest way to get my data into one of these databases?
  2. How does this relate to my overall UI?

  ### The solution

  These two questions are tightly related. David Nolen had this wonderful realization that when
  rendering the tree of your UI you are doing the same thing as when you're evaluating a graph
  query.

  Both the *UI render* and *running the query* are \"walks\" of a graph of data. Furthermore,
  if the UI tree participates in defining which bits of the query have distinct identity,
  then you can use *that* information to walk both (a sample tree and the current UI query) at
  the same time and take the process in reverse (from a *sample* UI tree into the desired graph
  database format)!

  ## Details

  So, let's see how that works.

  Placing a query on a component declares what data that component needs in order to render correctly.
  Components are little fragments of UI, so the queries on them are little fragments of the query.
  Thus, a `Person` component might declare it needs a person's name:

  "
  (dc/mkdn-pprint-source qd/Person)
  (dc/mkdn-pprint-source qd/person)
  "

  It is *really* important for you to understand that what you learned in the section on UI is not
  changed by adding the query. You can still use this component by just passing it data (e.g. the
  map from the first query simulation). Look at the source for the following card to convince you
  this is true:
  ")

(defcard using-component-with-query-passing-raw-data
  "
  This card is simply rendering:

  ```
    (qd/person {:person/name \"Sam\"})
  ```

  from the definitions above.
  "
  (qd/person {:person/name "Sam"}))

(defcard-doc
  "
  So the thing to understand is that the query is used by Fulcro to obtain data, but the data is just data, and
  the component itself doesn't care who provides it. In fact, as you'll see soon the root component of the UI
  is given then entire UI data tree, and your UI code must pick it apart and pass it through the tree.

  The next thing to notice is that a query fragment often does not have enough context to actually go find real data.
  For example, in the person case we have yet to ask \"which people/person\" by placing this fragment somewhere in a join.

  Examples might be:

  Get people that are my friends (e.g. relative to your login cookie):

  ```
  [{:current-user/friends (prim/get-query Person)}]
  ```

  Get people that work for the company (context of login):

  ```
  [{:company/active-employees (prim/get-query Person)}]
  ```

  Get a very specific user (using an ident):

  ```
  [{[:user/by-id 42] (prim/get-query Person)}]
  ```

  `fulcro-devguide.queries.query-demo` contains these components:

  "
  (dc/mkdn-pprint-source qd/Person)
  (dc/mkdn-pprint-source qd/person)
  (dc/mkdn-pprint-source qd/PeopleWidget)
  (dc/mkdn-pprint-source qd/people-list)
  (dc/mkdn-pprint-source qd/Root)
  "

  The above components make the following UI and query trees:

  <table>
    <tr>
      <td style=\"text-align:center;\"><strong>UI Tree</strong></td>
      <td style=\"padding-left:80px;\"></td>
      <td style=\"text-align:center;\"><strong>Query Tree</strong></td>
    </tr>
    <tr>
      <td><img src=\"svg/query-demo-ui-tree.svg\"></img></td>
      <td style=\"padding-left:80px;\"></td>
      <td><img src=\"svg/query-demo-query-tree.svg\"></img></td>
    </tr>
  </table>

  Note that the query tree does not have anything for the `PeopleWidget` because that component does not have a query.

  Pay careful attention to how the queries are
  composed (among stateful components). It is perfectly fine for a UI component to not participate in the query,
  in which case you must remember that the walk of render will not match the walk of the result data. This
  is shown in the above code: note how the root element asks for `:people`, and the render of the root
  pulls out that data and passes it down. Then note how `PeopleWidget` pulls out the entire properties
  and passes them on to the component that asked for those details.

  In fact, you can change `PeopleWidget` into a simple function. There is no specific need for it to be a component,
  as it is really just doing what the root element needed to do: pass the items from the list of people it queried
  into the final UI children (`Person`) that asked for the details found in each of the items. The middle widget isn't
  participating in the state tree generation, it is merely an artifact of rendering.

")

(defcard sample-rendering-with-result-data
  "Given the data at the bottom of this card and the UI code you read above, you get this result. Note that the
  database need not be normalized (though in real applications you'd want it to be)."
  (fn [state _] (qd/root @state))
  {:people [{:db/id 1 :person/name "Joe"}
            {:db/id 2 :person/name "Guy"}
            {:db/id 3 :person/name "Tammy"}]}
  {:inspect-data true})

(defcard-doc "

  ## Common mistakes with Queries

  ### Failing to reach the UI root

  Fulcro establishes the query context by rooting the entire query against the graph database. It looks for the query on
  the root component of your UI! There is no magic scanner that walks each component and figures things out. It is one
  big graph query against one big graph database. Make sure your queries compose all the way to
  the root! However, you get to *reason* about it through composition (through the component-local use use of `get-query`).
  Also note that all of the data
  gets passed into the Root component, and every level of the UI that asked for (or composed in) data
  must pick that apart and pass it down. Yet again, it is written as a component-local concern, but behaves globally.
  This also means you can pretend like your UI doesn't even have
  queries when working on your render functions. E.g. you can build your UI, pick apart a pretend
  result that you hand-generate, and then later add queries and populate the database and everything should work.

  ### Declaring a query that is not your own

  Beginners often make the mistake:

  ```
  (defsc Widget [this props]
    {:query (fn [] (prim/get-query OtherWidget))
     ...)
  ```

  because they think \"this component just needs what the child needs\". If that is truly the case, then
  Widget *should not have a query at all* (the parent should compose OtherWidget's into its own query). The most common
  location where this happens is at the root, where you may not want any specific data yourself.

  In that case, you *do* need a stateful component at the root, but you'll need to get the child data
  using a join, and then pick it apart via code and manually pass those props down:

  ```
  (defsc RootWidget [this {:keys [other] :as props}]
    {:query [{:other (prim/get-query OtherWidget)}]}
    (other-element other))
  ```

  ### Making a component when a function would do

  Sometimes you're just trying to clean up code and factor bits out. Don't feel like you have to wrap UI code in
  `defsc` if it doesn't need any support from React or Fulcro. Just write a function! `PeopleWidget` earlier in this
  document is a great example of this.

  ### Using a lambda for the query

  You can choose to code your query as a lambda:

  ```
  (defsc Component [this props]
    {:query (fn [] [:x])}
    ...)
  ```

  Doing so will disable a number of the error checks that `defsc` performs, and allows you to write logic within your
  query. However, you should understand more of the internals of Fulcro before attempting the latter.
  ")

(defcard-doc
  "
  ## Giving Your Component Identity

  The next important step is to associate your UI component with a function that can translate between the database
  state it will be used with, and the ident at which that data will be stored in the database. This is a critical
  feature for populating your database. The co-located query and ident *are the unifying glue* that simplify the
  entire story from initial application startup, to server fetch, to websocket push, and mutation simplicity.

  When you place an ident-generator *on* the component that has a query, you're telling Fulcro *how to normalize data*!
  What initially looks like a bunch of cruft on your UI component is the secret to simplifying your life from
  every other framework you've experienced to date! Pay attention!

  ### Adding Ident

  The code is rather simple:

  ```
  (defsc Person [this {:keys [db/id person/name]}]
    {:query [:db/id :person/name] ; or in lambda form (fn [] [:db/id :person/name])
     :ident [:person/by-id :db/id] ; or in lambda form (fn [] [:person/by-id id])
    ...)
  ```

  There are two ways to write it: As a template, and as a lambda.

  In template form, you write what looks like an ident, but the second element is the name of the property that holds
  the entity's ID: `[:person/by-id :db/id]` means `[:person/by-id (get props :db/id)]`.

  You may find it useful to have the ident use code instead (e.g. you might want to code your idents in a reusable
  function). In that case you can use a lambda, which will have the (possibly destructured) props from the `defsc` in
  scope:

  ```
  (defn person-ident [id] [:person/by-id id])

  (defsc Person [this {:keys [db/id]}]
    {:ident (fn [] [:person/by-id (person-ident id)])
    ...
  ```

  Some critical notes:

  - Your query must query for the data that `ident` needs. If you need `:db/id` to make the ident, then be sure to ask
  for it!
  - The template format will throw an error if you screw up. The lambda format disables error checking.
  - The table names (first element of the ident) are just keywords. Using the type/index style makes them easier to
  spot and understand.
  - Your root component is just that: The root of the tree and graph. Placing an Ident on it means you want the root
  itself to be placed within a table, but root is supposed to *hold* the tables. So, strange things will happen. Don't
  do it :)

  ### Choosing Table Names

  Remember that tables end up as keys in the root node of your graph database. Be very careful to namespace things so that
  as your application grows you don't have accidental name collisions. Naming a table `people` is a recipe for later
  disaster. You might even choose to use upper-cased names to make the tables more visible. I.e. `:PERSON/by-id`.

  ## Leveraging Identity

  Now comes the sweet stuff. Once a component has a query *and* an ident, Fulcro can *auto-normalize* a tree of data into
  a graph database format (using `prim/tree->db`)!

  ```
  Graph Query + Tree of Data === tree->db ===> Normalized app database
  ```

  This means if you send a query to a server and it responds with a matching
  tree of data, you don't have to figure out how to get it into state! If you have a websocket push of a known graph of
  data that matches a known query: same thing! You end up with a mechanism that will empower you to write UI (e.g.
  subscriptions to live entities on a server via websockets) that rivals
  the functionality of Meteor.js, but without being tied to the incidental complexities of their model!

  But let's start simple. Let's first make it easy to generate our initial tree of data for our application
  startup, which can now be auto-normalized!

  But first we need to show you how to
  [make a client](#!/fulcro_devguide.F_Fulcro_Client) so we can start to put it all together!
  ")

