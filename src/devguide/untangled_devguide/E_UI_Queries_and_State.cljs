(ns untangled-devguide.E-UI-Queries-and-State
  (:require-macros [cljs.test :refer [is]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.next.impl.parser :as p]
            [untangled-devguide.queries.query-editing :as qe]
            [om.dom :as dom]
            [cljs.reader :as r]
            [untangled-devguide.queries.query-demo :as qd]
            [devcards.util.edn-renderer :refer [html-edn]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

; TODO: Unions (advanced?)
; TODO: More complete covereage of queries in the context of UI.
; - Links (and the fact that a link-ONLY query still requires at least an empty thing in app state
; -
(defcard-doc
  "
  # UI, queries and state

  Now that you understand the database format, how you get data out of that database via
  the queries, and how you build parser code once you're ready to get some UI on the screen
  via all of those things?

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
  So the thing to understand is that the query is used by Om to obtain data, but the data is just data, and
  the component itself doesn't care who provides it. In fact, as you'll see soon the root component of the UI
  is given then entire UI data tree, and your UI code must pick it apart and pass it through the tree.

  The next thing to notice is that a query fragment often does not have enough context to actually go find real data.
  For example, in the person case we have yet to ask \"which people/person\" by placing this fragment somewhere in a join.

  Examples might be:

  Get people that are my friends (e.g. relative to your login cookie):

  ```
  [{:current-user/friends (om/get-query Person)}]
  ```

  Get people that work for the company (context of login):

  ```
  [{:company/active-employees (om/get-query Person)}]
  ```

  Get a very specific user (using an ident):

  ```
  [{[:user/by-id 42] (om/get-query Person)}]
  ```

  `untangled-devguide.queries.query-demo` contains these components:

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

  One common reason to create a component without a query (rather than a UI function) would be if you needed to store
  something using component local state. For example, a web form that wants to keep track of the user's progress, but
  will pass the submitted form data to another component for processing.

  So, this example will render correctly when the query result looks like what you see in the card below:
")

(defcard sample-rendering-with-result-data
  (fn [state _] (qd/root @state))
  {:people [{:db/id 1 :person/name "Joe"}
            {:db/id 2 :person/name "Guy"}
            {:db/id 3 :person/name "Tammy"}]}
  {:inspect-data true})

(defcard-doc "

  ## Common mistakes

  ### Failing to reach the UI root

  Om only looks for the query on the root component of your UI! Make sure your queries compose all the way to
  the root! Basically the Root component ends up with one big fat query for the whole UI, but you get to
  *reason* about it through composition (recursive use of `get-query`). Also note that all of the data
  gets passed into the Root component, and every level of the UI that asked for (or composed in) data
  must pick that apart and pass it down. In other words, you can pretend like you UI doesn't even have
  queries when working on your render functions. E.g. you can build your UI, pick apart a pretend
  result, then later add queries and everything should work.

  ### Putting an Ident on your root

  Your root component is just that: The root of the tree and graph. Placing an Ident on it means you want the root
  itself to be placed within a table, but root is supposed to *hold* the tables. So, strange things will happen.

  ### Declaring a query that is not your own

  Beginners often make the mistake:

  ```
  (defui Widget
       static om/IQuery
       (query [this] (om/get-query OtherWidget))
       ...)
  ```

  because they think \"this component just needs what the child needs\". If that is truly the case, then
  Widget should not have a query at all (the parent should compose OtherWidget's into its own query). The most common
  location where this happens is at the root, where you may not want any specific data yourself.

  In that case, you *do* need a stateful component at the root, but you'll need to get the child data
  using a join, and then pick it apart via code and manually pass those props down:

  ```
  (defui RootWidget
       static om/IQuery
       (query [this] [{:other (om/get-query OtherWidget)}])
       Object
       (render [this]
          (let [{:keys [other]} (om/props this)] (other-element other)))
  ```

  ### Making a component when a function would do

  Sometimes you're just trying to clean up code and factor bits out. Don't feel like you have to wrap UI code in
  `defui` if it doesn't need any support from React or Om. Just write a function! `PeopleWidget` earlier in this
  document is a great example of this.

  ")
