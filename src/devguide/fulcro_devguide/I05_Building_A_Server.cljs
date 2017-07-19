(ns fulcro-devguide.I05-Building-A-Server
  (:require-macros [cljs.test :refer [is]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [fulcro-devguide.state-reads.parser-1 :as parser1]
            [fulcro-devguide.state-reads.parser-2 :as parser2]
            [fulcro-devguide.state-reads.parser-3 :as parser3]
            [devcards.util.edn-renderer :refer [html-edn]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc deftest]]
            [cljs.reader :as r]
            [om.next.impl.parser :as p]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

; TODO: make external refs (e.g. to ss component) links

(defcard-doc
  "
  # Building the server

  The pre-built server components for Fulcro use Stuart Seirra's Component library. The server has no
  global state except for a debugging atom that holds the entire system. The project has already
  been set up with a basic server, and we'll cover what's in there, and what you need to write.

  You should have a firm understanding of Stuart's component library, since we won't be covering that
  in detail here.

  ## Constructing a base server

  The base server is trivial to create:

  ```
  (ns app.system
    (:require
      [fulcro.easy-server :as server]
      [app.api :as api]
      [om.next.server :as om]))

  (defn make-system []
    (server/make-fulcro-server
      ; where you want to store your override config file
      :config-path \"/usr/local/etc/app.edn\"

      ; Standard Om parser
      :parser (om/parser {:read api/api-read :mutate api/mutate})

      ; The keyword names of any components you want auto-injected into the parser env (e.g. databases)
      :parser-injections #{}

      ; Additional components you want added to the server
      :components {}))
  ```

  ### Configuring the server

  Server configuration requires two EDN files:

  - `resources/config/defaults.edn`: This file should contain a single EDN map that contains
  defaults for everything that the application wants to configure.
  - `/abs/path/of/choice`: This file can be named what you want (you supply the name when
  making the server). It can also contain an empty map, but is meant to be machine-local
  overrides of the configuration in the defaults. This file is required. We chose to do this because
  it keeps people from starting the app in an unconfigured production environment.

  ```
  (defn make-system []
    (server/make-fulcro-server
      :config-path \"/usr/local/etc/app.edn\"
      ...
  ```

  The only parameter that the default server looks for is the network port to listen on:

  ```
  { :port 3000 }
  ```

  ### Pre-installed components

  When you start an Fulcro server it comes pre-supplied with injectable components that your
  own component can depend on, as well as inject into the server-side Om parsing environment.

  The most important of these, of course, is the configuration itself. The available components
  are known by the following keys:

  - `:config`: The configuration component. The actual EDN value is in the `:value` field
  of the component.
  - `:handler`: The component that handles web traffic. You can inject your own Ring handlers into
  two different places in the request/response processing. Early or Late (to intercept or supply a default).
  - `:server`: The actual web server.

  The component library, of course, figures out the dependency order and ensures things are initialized
  and available where necessary.

  ### Making components available in the processing environment

  Any components in the server can be injected into the processing pipeline so they are
  available when writing your mutations and query procesing. Making them available is as simple
  as putting their component keyword into the `:parser-injections` set when building the server:

  ```
  (defn make-system []
    (server/make-fulcro-server
      :parser-injections #{:config}
      ...))
  ```

  ### Provisioning a request parser

  All incoming client communication will be in the form of Om Queries/Mutations. Om supplies
  a parser to do the low-level parsing, but you must supply the bits that do the real logic.
  Learning how to build these parts is relatively simple, and is the only thing you need
  to know to process all possible communications from a client.

  #### The Easy Way

  Fulcro includes a pre-built server parser and macros to install handlers for communications. These are
  covered in the
  [Getting Started Guide](https://github.com/fulcrologic/fulcro/blob/develop/GettingStarted.adoc)
  and can handle much of what you'd want to do.

  We recommend that you start with that approach, and hand-build a parser if you find
  yourself needing more in-depth support for query or mutation processing.

  #### About Om Parsers

  Om parsers require two things: A function to process reads, and a function to process mutations.
  These are completely open to your choice of implementation. They simply need to be functions
  with the signature:

  ```
  (fn [env key params] ...)
  ```

  - The `env` is the environment. On the *server* this will contain:
      - Anything components you've asked to be injected during construction. Usually some kind
      of database connection and configuration.
      - `ast`: An AST representation of the item being parsed.
      - `parser`: The Om parser itself (to allow you to do recursive calls)
      - `request`: The actual incoming request (with headers)
      - ... TODO: finish this
  - The `key` is the dispatch key for the item being parsed. We'll cover that shortly.
  - The `params` are any params being passed to the item being parsed.

  ")

(defcard-doc
  "
  ## Processing reads

  The Om parser is exactly what it sounds like: a parser for the query grammar. Now, formally
  a parser is something that takes apart input data and figures out what the parts mean (e.g.
  that's a join, that's a mutation call, etc.). In an interpreter, each time the parser finds
  a bit of meaning, it invokes a function to interpret that meaning and emit a result.
  In this case, the meaning is a bit of result data; thus, for Om to be able to generate a
  result from the parser, you must supply the \"read\" emitter.

  First, let's see what an Om parser in action.
  ")

(defcard om-parser
  "This card will run an Om parser on an arbitrary query, record the calls to the read emitter,
          and show the trace of those calls in order. Feel free to look at the source of this card.

          Essentially, it creates an Om parser:

          ```
           (om/parser {:read read-tracking})
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
          parser (om/parser {:read read-tracking})]
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

  ## Implementing read

  When building your server you must build a read function such that it can
  pull data to fufill what the parser needs to fill in the result of a query parse.

  The Om Next parser understands the grammar, and is written in such a way that the process
  is very simple:

  - The parser calls your `read` with the key that it parsed, along with some other helpful information.
  - Your read function returns a value for that key (possibly calling the parser recursively if it is a join).
  - The parser generates the result map by putting that key/value pair into
  the result at the correct position (relative to the query).

  Note that the parser only processes the query one level deep. Recursion (if you need it)
  is controlled by *your* read.
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
          parser (om/parser {:read read-tracking})]
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
(def parser-42 (om/parser {:read read-42}))

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
        (html-edn (:result @state))
        (dom/h4 nil "Database")
        (html-edn (:db @state))))))

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
(def property-parser (om/parser {:read property-read}))

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

(def my-parser (om/parser {:read flat-state-read}))

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
  object. The parser creates the result, and the recusion natually nested the
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

  Now, I feel compelled to mention a few things:

  - Keeping track of where you are in the parse (e.g. person can be generalized to
  'the current thing I'm working on') allows you to generalize this algorithm.
  - `db->tree` can still do everything we've done so far.
  - If you fully generalize the property and join parsing, you'll essentially recreate
  `db->tree`.

  So now you should be trying to remember why we're doing all of this. So let's talk
  about a case that `db->tree` can't handle: parameters.

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
  "Now we can try the following queries:"
  (dc/mkdn-pprint-source parser3/parse-result-mins)
  parser3/parse-result-mins
  (dc/mkdn-pprint-source parser3/parse-result-secs)
  parser3/parse-result-secs
  (dc/mkdn-pprint-source parser3/parse-result-ms)
  parser3/parse-result-ms)

