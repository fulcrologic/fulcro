(ns fulcro-devguide.H35-Server-Interactions-Query-Parsing
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [fulcro-devguide.state-reads.parser-1 :as parser1]
            [fulcro-devguide.state-reads.parser-2 :as parser2]
            [fulcro-devguide.state-reads.parser-3 :as parser3]
            [devcards.util.edn-renderer :refer [html-edn]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc deftest]]
            [cljs.reader :as r]
            [fulcro.client.impl.parser :as p]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(defcard-doc
  "
  # Query Parsing

  In the Basic Loading section we showed you the central entry points for responding to server queries:
  `defquery-root` and `defquery-entity`. These are fine for simple examples and for getting into your
  processing; however, to be truly data-driven you need to change how the server responds based
  on what the client actually *asked* for (in detail).

  So far, we've sort of been spewing entire entities back from the server without pruning them down
  to the actual query.

  All incoming client communication will be in the form of Query/Mutation expressions.
  There is no built-in recursive processing, but there is a parsing mechanism that you
  can use to process it, and there are a number of libraries that can also help.

  ## Avoiding Parsing

  If you're lucky, you can make use of a library to do this stuff for you. Here are some options we know about:

  - Fulcro SQL : A library that knows how to convert graph queries into SQL
  - Datomic : If you're lucky enough to be using Datomic, Fulcro's graph query syntax will run in their `pull` API
  - Pathom : A library by Wilker Lucio that can be used to build parsers on Fulcro's query syntax.

  Of these, Pathom is the most general, and allows you to easily process a query in a more abstract way. However, it
  really isn't that hard to parse the queries yourself.

  ## Doing the Parsing Yourself

  The most important item in the query processing is the received environment (`env`). On
  the server it contains:

  - Any components you've asked to be injected. Perhaps database and config components.
  - `ast`: An AST representation of the item being parsed.
  - `query`: The subquery (e.g. of a join)
  - `parser`: The query expression parser itself (which allows you to do recursive calls)
  - `request`: The full incoming Ring request, which will contain things like the headers, cookies, session, user agent info, etc.

  ### The server's parser is in `env`

  You get the server's parser in the query/mutation `env` that already hooked into your dispatch mechanism (e.g. defquery-root).

  Thus, if you run `(parser env [:x])` you should see a dispatch to your `defquery-root` on `:x`.

  The return value of the parser will be a map containing keys for all of the queried items
  that had a non-nil result from the dispatches.

  If you understand that, you can probably already write a simple recursive parse of a query. If
  you need a bit more hand-holding, then read on.

  NOTE: *You do not have to use the supplied parser*. Parsers are cheap. If you want to make one to deal with a particular
  graph, go for it! The `fulcro.client.primitives/parser` can make one.

  Now, let's get a feeling for the parser in general:

  ")

(defcard basic-parser
  "This card will run a parser on an arbitrary query, record the calls to the read emitter,
  and shows the trace of those calls in order. Feel free to look at the source of this card.

  Essentially, it creates a parser that dispatches reads to `read-tracking`:

  ```
   (prim/parser {:read read-tracking})
  ```

  where the `read-tracking` simply stores details of each call in an atom and shows those calls
  when parse is complete.

  The signature of a read function is:

  `(read [env dispatch-key params])`

   where the env contains the state of your application, a reference to your parser (so you can
                                                                                        call it recursively, if you wish), a query root marker, an AST node describing the exact
  details of the element's meaning, a path, and *anything else* you want to put in there if
  you call the parser recursively.

  Try some queries like these:

  - `[:a :b]`
  - `[:a {:b [:c]}]` (note that the AST is recursively built, but only the top keys are actually parsed to trigger reads)
  - `[(:a { :x 1 })]`  (note the value of params)
  "
  (fn [state _]
    (let [{:keys [v error]} @state
          trace (atom [])
          read-tracking (fn [env k params]
                          (swap! trace conj {:env          (assoc env :parser :function-elided)
                                             :dispatch-key k
                                             :params       params}))
          parser (prim/parser {:read read-tracking})]
      (dom/div nil
        (when error
          (dom/div nil (str error)))
        (dom/input #js {:type     "text"
                        :value    v
                        :onChange (fn [evt] (swap! state assoc :v (.. evt -target -value)))})
        (dom/button #js {:onClick #(try
                                     (reset! trace [])
                                     (swap! state assoc :error nil)
                                     (parser {} (r/read-string v))
                                     (swap! state assoc :result @trace)
                                     (catch js/Error e (swap! state assoc :error e))
                                     )} "Run Parser")
        (dom/h4 nil "Parsing Trace")
        (html-edn (:result @state)))))
  {}
  {:inspect-data false})

(defcard-doc
  "
  ### Injecting some kind of database

  In order to play with this on a server, you'll want to have some kind of state
  available. The most trivial thing you can do is just create a global top-level atom
  that holds data. This is sufficient for testing, and we'll assume we've done something
  like this on our server:

  ```
  (defonce state (atom {}))
  ```

  One could also wrap that in a Stuart Sierra component and inject it (as state) into the
  parser.

  Much of the remainder of this section assumes this.

  ## Read Dispatching

  When building your server there must be a read function that can
  pull data to fufill what the parser needs to fill in the result of a query. Fulcro supplies this by default
  and gives you the `defquery-*` macros as helpers to hook into it, but really it is just a multi-method.

  For educational purposes, we're going to walk you through implementing this read function yourself.

  The parser understands the grammar, and is written to work as follows:

  - The parser calls your `read` with the key that it parsed, along with some other helpful information.
  - Your read function returns a value for that key (possibly calling the parser recursively if it is a join).
  - The parser generates the result map by putting that key/value pair into
  the result at the correct position (relative to the query).

  Note that the parser only processes the query one level deep. Recursion (if you need it)
  is controlled by *you* calling the parser again from within the read function.
  ")

(defcard parser-read-trace
  "This card is similar to the prior card, but it has a read function that just records what keys it was
  triggered for. Give it an arbitrary legal query, and see what happens.

  Some interesting queries:

  - `[:a :b :c]`
  - `[:a {:b [:x :y]} :c]`
  - `[{:a {:b {:c [:x :y]}}}]`

  "
  (fn [state _]
    (let [{:keys [v error]} @state
          trace (atom [])
          read-tracking (fn [env k params]
                          (swap! trace conj {:read-called-with-key k}))
          parser (prim/parser {:read read-tracking})]
      (dom/div nil
        (when error
          (dom/div nil (str error)))
        (dom/input #js {:type     "text"
                        :value    v
                        :onChange (fn [evt] (swap! state assoc :v (.. evt -target -value)))})
        (dom/button #js {:onClick #(try
                                     (reset! trace [])
                                     (swap! state assoc :error nil)
                                     (parser {} (r/read-string v))
                                     (swap! state assoc :result @trace)
                                     (catch js/Error e (swap! state assoc :error e))
                                     )} "Run Parser")
        (dom/h4 nil "Parsing Trace")
        (html-edn (:result @state)))))
  {}
  {:inspect-data false})

(defcard-doc
  "
  In the card above you should have seen that only the top-level keys trigger reads.

  So, the query:

  ```clj
  [:kw {:j [:v]}]
  ```

  would result in a call to your read function on `:kw` and `:j`. Two calls. No
  automatic recursion. Done. The output value of the *parser* will be a map (that
  parse creates) which contains the keys (from the query, copied over by the
  parser) and values (obtained from your read):

  ```clj
  { :kw value-from-read-for-kw :j value-from-read-for-j }
  ```

  Note that if your read accidentally returns a scalar for `:j` then you've not
  done the right thing...a join like `{ :j [:k] }` expects a result that is a
  vector of (zero or more) things *or* a singleton *map* that contains key
  `:k`.

  ```clj
  { :kw 21 :j { :k 42 } }
  ; OR
  { :kw 21 :j [{ :k 42 } {:k 43}] }
  ```

  Dealing with recursive queries is a natural fit for a recusive algorithm, and it
  is perfectly fine to invoke the `parser` function to descend the query. In fact,
  the `parser` is passed as part of your environment.

  So, the read function you write will receive three arguments, as described below:

  1. An environment containing:
      + `:ast`: An abstract syntax *tree* for the element, which contains:
         + `:type`: The type of node (e.g. :prop, :join, etc.)
         + `:dispatch-key`: The keyword portion of the triggering query element (e.g. :people/by-id)
         + `:key`: The full key of the triggering query element (e.g. [:people/by-id 1])
         + `:query`: (same as the query in `env`)
         + `:children`: If this node has sub-queries, will be AST nodes for those
         + others...see documentation
      + `:parser`: The query parser
      + `:query`: **if** the element had one E.g. `{:people [:user/name]}` has `:query` `[:user/name]`
      + Components you requested be injected
  2. A dispatch key for the item that triggered the read (same as dispatch key in the AST)
  3. Parameters (which are nil if not supplied in the query)

  It must return a value that has the shape implied the grammar element being read.

  So, lets try it out.
  ")

(defn read-42 [env key params] {:value 42})
(def parser-42 (prim/parser {:read read-42}))

(defcard-doc
  "
  ### Reading a keyword

  If the parser encounters a keyword `:kw`, your function will be called with:

  ```clj
  (your-read
    { :dispatch-key :kw :parser (fn ...) } ;; the environment: parser, etc.
    :kw                                   ;; the keyword
    nil) ;; no parameters
  ```

  your read function should return some value that makes sense for
  that spot in the grammar. There are no real restrictions on what that data
  value has to be in this case. You are reading a simple property.
  There is no further shape implied by the grammar.
  It could be a string, number, Entity Object, JS Date, nil, etc.

  Due to additional features of the parser, *your return value must be wrapped in a
  map with the key `:value`*. If you fail to do this, you will get nothing
  in the result.

  Thus, a very simple read for props (keywords) could be:

  ```clj
  (defn read [env key params] { :value 42 })
  ```

  below is a devcard that implements exactly this `read` and plugs it into a
  parser like this:"
  (dc/mkdn-pprint-source read-42)
  (dc/mkdn-pprint-source parser-42))

(defn parser-tester [parser]
  (fn [state _]
    (let [{:keys [v error]} @state]
      (dom/div nil
        (dom/input #js {:type     "text"
                        :value    v
                        :onChange (fn [evt] (swap! state assoc :v (.. evt -target -value)))})
        (dom/button #js {:onClick #(try
                                     (swap! state assoc :error "" :result (parser {:state (atom (:db @state))} (r/read-string v)))
                                     (catch js/Error e (swap! state assoc :error e))
                                     )} "Run Parser")
        (when error
          (dom/div nil (str error)))
        (dom/h4 nil "Query Result")
        (pr-str (:result @state))
        (dom/h4 nil "Database")
        (pr-str (:db @state))))))

(defcard property-read-for-the-meaning-of-life-the-universe-and-everything
  "This card is using the parser/read pairing shown above (the read returns
  the value 42 no matter what it is asked for). Run any query you
  want in it, and check out the answer.

  This card just runs `(parser-42 {} your-query)` and reports the result.

  Some examples to try:

  - `[:a :b :c]`
  - `[:what-is-6-x-7]`
  - `[{:a {:b {:c {:d [:e]}}}}]` (yes, there is only one answer)
  "
  (parser-tester parser-42)
  {:db {}})

(defn property-read [{:keys [state]} key params] {:value (get @state key :not-found)})
(def property-parser (prim/parser {:read property-read}))

(defcard-doc
  "
  So now you have a read function that returns the meaning of life the universe and
  everything in a single line of code! But now it is obvious that we need to build
  an even bigger machine to understand the question.

  If your server state is just a flat set of scalar values with unique keyword
  identities, then a better read is similarly trivial.

  The read function:
  "
  (dc/mkdn-pprint-source property-read)
  "
  Just assumes the property will be in the top-level of some injected state atom.
  ")

(defcard trivial-property-reader
  "This card is using the `property-read` function above in a parser.

  The database we're emulating is shown at the bottom of the card, after the
  result.

  Run some queries and see what you get. Some suggestions:

  - `[:a :b :c]`
  - `[:what-is-6-x-7]`
  - `[{:a {:b {:c {:d [:e]}}}}]` (yes, there is only one answer)
  "
  (parser-tester property-parser)
  {:db {:a 1 :b 2 :c 99}}
  {:inspect-data true})

(def flat-app-state (atom {:a 1 :user/name "Sam" :c 99}))

(defn flat-state-read [{:keys [state parser query] :as env} key params]
  (if (= :user key)
    {:value (parser env query)}                             ; recursive call. query is now [:user/name]
    {:value (get @state key)}))                             ; gets called for :user/name :a and :c

(def my-parser (prim/parser {:read flat-state-read}))

(defcard-doc
  "
  The result of those nested queries is supposed to be a nested map. So, obviously we
  have more work to do.

  ### Reading a join

  Your state probably has some more structure to it than just a flat
  bag of properties. Joins are naturally recursive in syntax, and
  those that are accustomed to writing parsers probably already see the solution.

  First, let's clarify what the read function will receive for a join. When
  parsing:

  ```clj
  { :j [:a :b :c] }
  ```

  your read function will be called with:

  ```clj
  (your-read { :state state :parser (fn ...) :query [:a :b :c] } ; the environment
             :j                                                 ; keyword of the join
             nil) ; no parameters
  ```

  But just to prove a point about the separation of database format and
  query structure we'll implement this next example
  with a basic recursive parse, *but use more flat data* (the following is live code):

  "
  (dc/mkdn-pprint-source flat-app-state)
  (dc/mkdn-pprint-source flat-state-read)
  (dc/mkdn-pprint-source my-parser)
  "
  The important bit is the `then` part of the `if`. Return a value that is
  the recursive parse of the sub-query. Otherwise, we just look up the keyword
  in the state (which as you can see is a very flat map).

  The result of running this parser on the query shown is:

  "
  (my-parser {:state flat-app-state} '[:a {:user [:user/name]} :c])
  "

  The first (possibly surprising thing) is that your result includes a nested
  object. The parser creates the result, and the recursion naturally nested the
  result correctly.

  Next you should remember that join implies a there could be one OR many results.
  The singleton case is fine (e.g. putting a single map there). If there are
  multiple results it should be a vector.

  In this case, we're just showing that you can use the parser recursively
  and it in turn will call your read function again.
  In a real application, your data will not be this flat, so you
  will almost certainly not do things in quite this
  way.

  So, let's put a little better state in our application, and write a
  more realistic parser.

  ### A Non-trivial, recursive example

  Let's start with the following hand-normalized application state:

  "
  (dc/mkdn-pprint-source parser1/app-state)
  "
  Our friend `db->tree` could handle queries against this database,
  but let's implement it by hand.

  Say we want to run this query:

  "
  (dc/mkdn-pprint-source parser1/query)
  "

  From the earlier discussion you see that we'll have to handle the
  top level keys one at a time.

  For this query there are only two keys to handle: `:friends`
  and `:window-size`. So, let's write a case for each:

  "
  (dc/mkdn-pprint-source parser1/read)
  "

  The default case is `nil`, which means if we supply an errant key in the query no
  exception will happen.

  when we run the query, we get:

  "
  (parser1/parser {:state parser1/app-state} parser1/query)
  "

  Those of you paying close attention will notice that we have yet to need
  recursion. We've also done something a bit naive: select-keys assumes
  that query contains only keys! What if app state and query were instead:

  "
  (dc/mkdn-pprint-source parser2/app-state)
  (dc/mkdn-pprint-source parser2/query)
  "

  Now things get interesting, and I'm sure more than one reader will have an
  opinion on how to proceed. My aim is to show that the parser can be called
  recursively to handle these things, not to find the perfect structure for the
  parser in general, so I'm going to do something simple.

  The primary trick I'm going to exploit is the fact that `env` is just a map, and
  that we can add stuff to it. When we are in the context of a person, we'll add
  `:person` to the environment, and pass that to a recursive call to `parser`.

  "
  (dc/mkdn-pprint-source parser2/read)
  "
  and running the query gives the expected result:

  "
  (parser2/parser {:state parser2/app-state} parser2/query)
  "

  All of the code shown here is being actively pulled (and run) from `fulcro-devguide.state-reads.parser-2`.

  ## Parameters

  In the query grammar most kinds of rules accept parameters. These are intended
  to be combined with dynamic queries that will allow your UI to have some control
  over what you want to read from the application state (think filtering, pagination,
  sorting, and such).

  As you might expect, the parameters on a particular expression in the query
  are just passed into your read function as the third argument.
  You are responsible for both defining and interpreting them.
  They have no rules other than they are maps.

  To read the property `:load/start-time` with a parameter indicating a particular
  time unit you might use:

  ```clj
  [(:load/start-time {:units :seconds})]
  ```

  this will invoke read with:

  ```clj
  (your-read env :load/start-time { :units :seconds})
  ```

  the implication is clear. The code is up to you. Let's add some quick support for this
  in our read (in fulcro-devguide.state-reads.parser-3):
  "
  (dc/mkdn-pprint-source parser3/app-state)
  (dc/mkdn-pprint-source parser3/read)
  (dc/mkdn-pprint-source parser3/parser)
  "Now we can try the following queries:

  Query:"
  (dc/mkdn-pprint-source parser3/parse-result-mins)
  "Result: "
  parser3/parse-result-mins
  "Query: "
  (dc/mkdn-pprint-source parser3/parse-result-secs)
  "Result: "
  parser3/parse-result-secs
  "Query: "
  (dc/mkdn-pprint-source parser3/parse-result-ms)
  "Result: "
  parser3/parse-result-ms)

(defcard-doc
  "
  ## Using a Completely Custom Parser

  Most of this section assumed you're entering your server code via the built-in parser. Note that it is possible to use an
  alternate (custom) parser at any phase of parsing. In fact, you can even install a custom parser in your server
  (though then the macros for defining mutations and query handlers won't work for you).

  Just remember that at any time in parsing you may change over to using an alternate instance of a parser to continue
  processing. Parsers can be constructed using the `prim/parser` function.

  # External Libraries

  ## [Pathom](https://github.com/wilkerlucio/pathom)

  A really nice library for building recursive Fulcro query parsers. It has a good model for building parsers that can
  bridge everything from REST services to microservice architectures. In general if you need to interpret your UI queries,
  this tool can be very useful.


  ## [Fulcro-SQL](https://github.com/fulcrologic/fulcro-sql)

  A library that can run Fulcro graph queries against SQL databases. This library lets you define your joins in relation
  to the Fulcro join notion. It can walk to-one, to-many, and many-to-many joins in an SQL database in response to a
  Fulcro join. This allows it to handle many Fulcro queries as graph queries against your SQL database with just a little
  configuration and extra code.

  ")
