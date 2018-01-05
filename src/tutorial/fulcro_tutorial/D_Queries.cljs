(ns fulcro-tutorial.D-Queries
  (:require-macros
    [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim]
            [fulcro.client.impl.parser :as p]
            [fulcro-tutorial.queries.query-editing :as qe]
            [fulcro.client.dom :as dom]
            [cljs.reader :as r]
            [fulcro-tutorial.queries.query-demo :as qd]
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
  It will really help you with Fulcro queries.

  ## A quick note on quoting

  Quoting is not an query thing, it is a clj(s) thing. The syntax of Fulcro queries is just data, but it
  uses things (like symbols and lists) that the compiler would like to give different meaning to than
  we need.

  Quoting is a standard feature of LISP-like languages because the language itself is just data. When you
  use a symbol, you're asking the compiler to write code to look up the value in the environment. But, if you're
  writing code to manipulate code *as* data, you need a way to just get the symbol itself. Quoting is how you
  do that. For example `a` means \"get the value held in symbol a\", whereas `'a` means \"give me the
  literal language symbol `a`\". The backquote version means a bit more. For starters it includes/resolve namespaces.
  (e.g. ``prim/Ident` would resolve to the symbol `fulcro.client.primitives/Ident` if it had been required and aliased to `prim`).

  If you have not done much in the way of macro programming you may be confused by the
  quoting often seen in these queries. There is an [appendix](/#!/fulcro_tutorial.Z_Query_Quoting) on this, but
  here are some quick notes:

  - Using `'` quotes the form that follows it, making it literal.
  - Using a backquote in front of a form is a syntax quote. It will namespace all symbols, and allows
    you to unquote subforms using `~`. You'll sometimes also see ~' in front of a non-namespaced
    symbol *within* a syntax quoted form. This prevents a namespace from being added (unquote a form
    that literally quotes the symbol).

  ## Understanding queries - It's All Relative

  *The most important point* to understand is that queries are *local* and *relative*. A person component might ask
  for name and age; however, there is no way (in isolation) for that component (as a class) to know *which* person is to be
  queried. That is the parent's job (to provide the context). This flows up the UI tree to the root. The root query is
  the only component query in your application that is *absolute*. It is anchored to the graph database.

  Traversal of the query is traversal of your graph database starting at root.

  Once components are on-screen, they have a known identity (that can be found using their `ident` function on their props).
  At that point it is possible to run a query starting at that component (e.g. that table entry) in the database. Technically,
  if you know the ident of something you can manually construct a query to start there, but that is an advanced topic, and
  is by no means the norm.

  Read this section about a dozen times. It really is critical to understanding the model.

  ## Understanding queries - Properties

  Except for unions, queries are represented as vectors.

  To read simple properties, just include the keyword name of that property in a vector.

  ```
  [:person/name :person/age] ; Query for properties of (some) person (we don't know who yet)
  ```

  When you run a query against the database (which will have some relative context), you get back a map populated with
  the items you requested that represent that data. The values are unconstrained by a schema. They could be string, numbers,
  objects, maps, etc.

  This is *much* simpler than it sounds:

  ```
  [:person/name :person/age] + {:db/id 3 :person/name \"Joe\"} ====query engine===> {:person/name \"Joe\"}
        Query (I want)       +      Context (db has)                                   Result (I get)
  ```

  ")

(defcard query-example-1
  "
  This query card has a database that contains exactly one thing: the details about a person.

  Play with the query and ask for this person's age and database ID. Also try asking for something that
  isn't there (it is a harmless no-op).

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

  Joins are how you create the tree-like structure of your graph query to match the tree-like structure of your
  components that *have* the queries. It is what generates the relative connections from component to component.

  Queries can indicate [joins](/#!/fulcro_devguid.Z_Glossary) by
  nesting a *property name* (a keyword) in a map with *exactly one* key, whose value
  is a subquery for what you want to pull out of that (or those) database items.

  The to-one or to-many nature of the join is implied by the content of the database (if there is one thing there
  then it is to-one, if there is a vector of things, it is to-many).

  For example to pull a chart that might contains a sequence of (x,y,color) triples,
  you might write:

  ```
  [ {:chart [:x :y :color]} ]
  ```

  The join key indicates that a keyword of that name exists on the data being queried,
  and the value in the database at that keyword is either a map (for a to-one relation)
  or a vector (to-many).

  IMPORATANT: You will be making up a lot of join keywords. If your join is also a join in your database, then that's
  what you'll use, but joins among UI-only components need some kind of data name to use as the keyword for the join. You just
  make those up (hopefully with good meaningful names).

  The result for queries are always maps, keyed by the query selectors. In this case
  let's pretend like there is a single chart, which has multiple data points. The
  result of the query might be:

  ```
  { :chart [ {:x 1 :y 2 :color \"red\"} {:x 4 :y 3 :color \"red\"} ... ]}
  ```

  If the join were something like this:

  ```
  [ {:modal-dialog [:message]} ]
  ```

  against a *to one* join (the corresponding database has single item at that location), resulting in:

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

  The query syntax supports parameterization. We'll cover that more in a section on dynamic queries. For now,
  know that Fulcro's default query engine does not use or parse query parameters in the client for reads. It does
  use them when interacting with servers, and they can be useful if you make more advanced components with dynamic
  queries and custom query engine rules.

  We'll defer talking about them until we talk about [server side interactions](#!/fulcro_tutorial.H_Server_Interactions).
  [Mutations](#!/fulcro_tutorial.G_Mutation) are a place where parameters are much more common and useful. Plan on using them there.

  ### Looking up by ident

  By now you are very familiar with the fact that idents in Fulcro are just the location of a normalized entity in the
  graph database. The first element is a keyword naming the type/table and the second is some kind of
  id value (e.g. keyword, numeric id, etc.). It is legal to
  use them *in place of a property name* to access a specific instance
  of some object in the database. This also provides an explicit context from
  which the remainder of the query can be evaluated.
  ")

(defcard ident-based-queries
  "The database in this card contains various tables. Use idents in queries to experiement with this
  query feature. Note that even though you are querying by ident (which is a vector) you *still need*
  the containing vector (which is the top-level container for queries).

   NOTE: The EDN renderer in these cards sometimes misformats idents by vertically moving the closing bracket down a line.
   The *query result* of the suggested queries is keyed by the ident:

   ```
   { [:people/by-id 2] ... }
   ```

   might display as:

   ```
   { [:people/by-id 2    ...
                     ]         }}
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
  This is very similar to the ident based query above, except in this case there is no ID, since the
  item in question is in a root-level property.

  Note that `_` is a symbol, which the compiler will normally try to look up for you. So, it will need to be quoted if
  used in your code. In the section on the database format we talked about top-level
  data. Links are the way to access them anywhere from the UI because they can appear
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
   :db           {:main-menu  {:title "My Title"}
                  :chart      {:name "Combined Graph" :data [:statistics :performance]}
                  :statistics {:performance {
                                             :cpu-usage [45 15 32 11 66 44]
                                             }}}
   :id           "query-example-links"}
  {:inspect-data false})


(defcard-doc
  "
  ### Union queries

  Union queries are special queries that always exist in the context of a join, and describe that the join
  itself may point to any number of different kinds of things. Think of a UI router: when you're on
  TAB A, you'd want the query for A. When you're on TAB B, you'd want that query. Another
  common example is a to-many data feed that might contain comments, images, or links. The query to use
  is dependent on the *data that is in context during graph traversal*.

  When a union query is reached during graph traversal, the ident found is used to determine the *type* of thing
  at that location with the following simple convention: The first element of the ident (which must be a keyword)
  indicates the *type*, and the union query itself is a map *keyed* by type. Thus the ident keyword serves as the resolver for the
  actual query to use against the real item found in the database.

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
   - `[{[:panelA 1] {:panelC [:sticky], :panelA [:boo], :panelB [:goo]}}]`  (access by known ident)
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
  In this section we're using the function `db->tree` to run the queries. This utility can
  (obviously) understand the basic bits of query syntax and retrieve data, and serves as the core
  internal function for processing UI queries.

  Now move on to [UI queries and state](#!/fulcro_tutorial.E_UI_Queries_and_State).
  ")
