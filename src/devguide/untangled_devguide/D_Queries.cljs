(ns untangled-devguide.D-Queries
  (:require-macros
    [cljs.test :refer [is]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.next.impl.parser :as p]
            [untangled-devguide.queries.query-editing :as qe]
            [om.dom :as dom]
            [cljs.reader :as r]
            [untangled-devguide.queries.query-demo :as qd]
            [devcards.util.edn-renderer :refer [html-edn]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(defcard-doc
  "
  # Queries

  Once you've got some data in a database on the client then the next thing you need to know
  is how to get it back out (and eventually onto the screen). The \"getting it out\" is
  a combination of writing queries *and* writing some of the code that actually
  helps with the retrieval.

  First, we'll tackle the queries.

  ## Query syntax

  Queries are written with a variant of Datomic pull syntax.

  For reference, here are the defined grammar elements:
  ```clj
  [:some/key]                              ;;prop
  [(:some/key {:arg :foo})]                ;;prop + params
  [{:some/key [:sub/key]}]                 ;;join + sub-select
  [:some/k1 :some/k2 {:some/k3 ...}]       ;;recursive join
  [({:some/key [:sub/key]} {:arg :foo})]   ;;join + params
  [[:foo/by-id 0]]                         ;;reference
  [(fire-missiles!)]                       ;;mutation
  [(fire-missiles! {:target :foo})]        ;;mutation + params
  { :photo [...subquery...]
    :video [...subquery...]
    :comment [...subquery...] }             ;;union
  ```

  *RECOMMENDATION*: Even if you do not plan to use Datomic, it is highly recommended
  that you go through the [Datomic Pull Tutorial](http://docs.datomic.com/pull.html).
  It will really help you with Om Next queries.

  ## A quick note on quoting

  Quoting is not an Om thing, it is a clj(s) thing. The syntax of Om queries is just data, but it
  uses things (like symbols and lists) that the compiler would like to give different meaning to than
  we need.

  If you have not done much in the way of macro programming you may be confused by the
  quoting often seen in these queries. There is an [appendix](/#!/untangled_devguide.Z_Query_Quoting) on this, but
  here are some quick notes:

  - Using `'` quotes the form that follows it, making it literal.
  - Using a backquote in front of a form is a syntax quote. It will namespace all symbols, and allows
    you to unquote subforms using `~`. You'll sometimes also see ~' in front of a non-namespaced
    symbol *within* a syntax quoted form. This prevents a namespace from being added (unquote a form
    that literally quotes the symbol).

  ## Understanding queries - Properties

  Except for unions, queries are represented as vectors.

  To read simple properties, just include the keyword name of that property in a vector.

  ```
  [:a :b] ; Query for properties :a and :b
  ```

  When you run a query, you are supposed to get back a map keyed by the query keywords, with values
  that represent that data. The values are unconstrained by a schema. They could be string, numbers,
  objects, maps, etc.
  ")

(defcard query-example-1
  "
  This query card has a database that contains exactly one thing: the details about a person.

  Play with the query and ask for this person's age and database ID.

  Notes:

  - The query has to be a vector
  - The result is a map, with keys that match the selectors in the query.
  "
  qe/query-editor
  {:query        "[:person/name]"
   :query-result {}
   :db           {:db/id 1 :person/name "Sam" :person/age 23 :person/favorite-date (js/Date.)}
   :id           "query-example-1"}
  {:inspect-data false})


(defcard-doc
  "
  ## Joins

  Queries can indicate [joins](/#!/untangled_devguid.Z_Glossary) by
  nesting a *property name* (a keyword) in a map with *exactly one* key, whose value
  is a subquery for what you want to pull out of that (or those) database items.

  For example to pull a chart that might contains a sequence of (x,y,color) triples,
  you might write:

  ```
  [ {:chart [:x :y :color]} ]
  ```

  The join key indicates that a keyword of that name exists on the data being queried,
  and the value in the database at that keyword is either a map (for a to-one relation)
  or a vector (to-many).

  The result for queries are always maps, keyed by the query selectors. In this case
  let's pretend like there is a single chart, which has multiple data points. The
  result of the query would be:

  ```
  { :chart [ {:x 1 :y 2 :color \"red\"} {:x 4 :y 3 :color \"red\"} ... ]}
  ```

  If the join were something like this:

  ```
  [ {:modal-dialog [:message]} ]
  ```

  ...one might expect that this is a *to one* join (and the corresponding database would have
  single item at that location), resulting in:

  ```
  { :modal-dialog {:message \"You screwed up!. Click OK to continue.\" } }
  ```

  ")

(defcard query-example-2
  "
  This query card has a more interesting database in it with some performance statistics
  linked into a table and chart. Note that
  the supplied query for the table is for the disk data, while the query for the
  chart combines multiple bits of data.

  Play with the query a bit to make sure you understand it (e.g. erase it and try to write it from scratch).

  Again note that the query result is a map in tree form, and remember that
  a tree is exactly what you need for a UI!

  Some other interesting queries to try:

  - `[:table]`
  - `[{:chart [{:data [:cpu-usage]}]}]`
  - `[ [:statistics :performance] ]`
  - `[{[:statistics :performance] [:disk-activity]}]`
  "
  qe/query-editor
  {:query        "[{:table [:name {:data [:disk-activity]}]}   {:chart [:name {:data [:disk-activity :cpu-usage]}]}]"
   :query-result {}
   :db           {:table      {:name "Disk Performance Table" :data [:statistics :performance]}
                  :chart      {:name "Combined Graph" :data [:statistics :performance]}
                  :statistics {:performance {
                                             :cpu-usage        [45 15 32 11 66 44]
                                             :disk-activity    [11 34 66 12 99 100]
                                             :network-activity [55 87 20 01 22 82]}}}
   :id           "query-example-2"}
  {:inspect-data false})

(defcard-doc "

  ## More advanced queries

  ### Parameters in the query

  Untangled takes the approach to not use, or parse query parameters in the client for reads. Adding query param
  parsing introduces complexity in the parser, and you simply do not need it. In many cases, you will
  find that follow on reads from a mutation solve your problem with less complexity. However, in
  [server side interactions](#!/untangled_devguide.H_Server_Interactions),
  you will find that query params can be used for reads. And since you will implement your own read parser,
  you have control of how those are dealt with. [Mutations](#!/untangled_devguide.G_Mutation)
  are a place where parameters are very useful. Plan on using them there.

  ### Looking up by ident

  An Ident is a vector with 2 elements. The first is a keyword and the second is some kind of
  value (e.g. keyword, numeric id, etc.). These are the same idents you saw in the App Database section,
  and can be used in place of a property name to indicate a specific instance
  of some object in the database (as property access or a join). This provides explicit context from
  which the remainder of the query can be evaluated.
  ")

(defcard ident-based-queries
  "The database in this card contains various tables. Use idents in queries to experiement with this
  query feature. Note that even though you are querying by ident (which is a vector) you *still need*
  the containing vector (which is the top-level container for queries).

   NOTE: The edn renderer sometimes misformats idents by vertically moving the closing bracket down a line.
   The *query result* of the suggested queries is keyed by the ident:

   ```
   { [:people/by-id 2] ... }
   ```

   Some interesting queries to try:

   - `[ [:people/by-id 2] ]`
   - `[{[:people/by-id 1] [:person/age]} {[:people/by-id 3] [:person/name]}]`
  "
  qe/query-editor
  {:query        "[ ]"
   :query-result {}
   :db           {:people/by-id {1 {:person/name "Sally" :person/age 33}
                                 2 {:person/name "Jesse" :person/age 43}
                                 3 {:person/name "Bo" :person/age 13}}}
   :id           "ident-based-queries"}
  {:inspect-data false})

(defcard-doc
  "### Using links

  Links look like idents, but they replace the ID portion with the special symbol `_`.
  One way of thinking about this is, go get `:my-key`, which is at the top level, and
  `_` implies that you don't care about the id. This is very similar to the ident based
  query above, except that in the ident query, you do care about the id (the 2nd tuple value).
  This pattern of `[[:first :second]]` is a lot like saying, `(get-in app-db [:first :second])`
  Furthemore, `[{[:first :second] [:prop-a :prop-b]]}]` is like
  `(-> app-db (get-in [:first :second]) (select-keys [:prop-a :prop-b]))`. Remember the constraint
  about having two values in the ident? Well, the `_` allows us to use an *I'm not worried about the
  second value*. This is a really powerful notion when you apply it to nested queries.

  Note that symbols need to be quoted, so a query including a link will need to leverage
  quoting in normal syntax. In the section on the database format we talked about top-level
  singletons. Links are the way to access them anywhere from the UI because they can appear
  at any level in the nested query.


  ")

(defcard query-example-links
  "In the example database below we emulate the idea that the UI might have a main
   screen. The UI query might then end up being nested several layers deep.
   A link query can be used to grab top-level data even if that portion of
   the query is nested:

   So, an interesting query could be:

   - [{:main-menu [:title {[:chart _] [:name]}]}]
   "
  qe/query-editor
  {:query        "[{:main-menu [:title {[:chart _] [:name]}]}]"
   :query-result {}
   :db           {:main-menu { :title "My Title" }
                  :chart      {:name "Combined Graph" :data [:statistics :performance]}
                  :statistics {:performance {
                                             :cpu-usage        [45 15 32 11 66 44]
                                             }}}
   :id           "query-example-links"}
  {:inspect-data false})


(defcard-doc
  "
  ### Union queries

  When a component is showing a sequence of things and each of those things might be different, then you need
  a union query. Basically, it is a *join*, but it names all of the alternative things that might appear
  in the resulting collection. Instead of being a vector, unions are maps of vectors (where each value in the map
  is the query for the keyed kind of thing). They look like multiple joins all merged into a single map.

  The ident (in the query, or in the database) for the item in question is used to determine the *type* of thing
  at that location with the following convention: The keyword (first) element of the ident indicates the *type*,
  and the union query itself is *keyed* by that type. Thus the ident keyword serves as the resolver for the
  actual selector query to use against the real item in the database.

  The following query card with data can be used to reinforce your understanding.
  ")

(defcard union-queries
  "The database in this card contains some pretend UI panels of different types (and assumes you could
  have more than one of each). There is a panel of \"type\" `:panelA`, whose ID is 1. The same for B and
  C. The `:current-panel` bit of state is a singleton ident that is meant to stand for the panel I
  want to, say, show on the screen. The `:panels` bit of state is a list of panels.

  Remember that a map with multiple k-v pairs is a union query, and you should think of it as \"this
  the a map of sub-query selectors to apply to the object you find in the database, and you pick
  one via the keyword of the ident of that object\".

   Some interesting queries to try:

   - `[{:panels {:panelC [:sticky], :panelA [:boo], :panelB [:goo]}}]` (access a list)
   - `[{:current-panel {:panelC [:sticky], :panelA [:boo], :panelB [:goo]}}]`  (access a singleton)
   - `[{[:panelA 1] {:panelC [:sticky], :panelA [:boo], :panelB [:goo]}}]`  (access a singleton by ident)
  "
  qe/query-editor
  {:query        "[{:panels {:panelA [:boo] :panelB [:goo] :panelC [:sticky]}}]"
   :query-result {}
   :db           {:panels        [[:panelA 1] [:panelB 1] [:panelC 1]]
                  :panelA        {1 {:boo 42}}
                  :panelB        {1 {:goo 8}}
                  :panelC        {1 {:sticky true}}
                  :current-panel [:panelA 1]}
   :id           "union-queries"}
  {:inspect-data false})

(defcard-doc
  "
  ## Final notes

  In case you're interested:
  In this section we're using the Om function `db->tree` to run the queries. This utility can
  (obviously) understand the basic bits of query syntax and retrieve data. It is insufficient
  for the full guts of a real application, but it is a wonderful helper that greatly simplifies
  the task.

  Now move on to [UI queries and state](#!/untangled_devguide.E_UI_Queries_and_State).
  ")
