(ns untangled-devguide.K-Testing
  (:require-macros [cljs.test :refer [is]]
                   [untangled-devguide.tutmacros :refer [untangled-app]])
  (:require [devcards.core :as dc :include-macros true :refer-macros [defcard defcard-doc dom-node]]
            [cljs.reader :as r]
            [om.next.impl.parser :as p]))

(defcard-doc
  "# Testing

  Untangled includes a library for great BDD. The macros in untangled spec wrap clojure/cljs test, so that you may
  use any of the features of that core library. The specification DSL makes it much easier to read the
  tests, and also includes a number of useful features:

  - Outline rendering
  - Left-to-right assertions
  - More readable output, such as data structure comparisons on failure (with diff notation as well)
  - Real-time refresh of tests on save (client and server)
  - Seeing test results in any number of browsers at once
  - Mocking of normal functions, including native javascript (but as expected: not macros or inline functions)
      - Mocking verifies call sequence and call count
      - Mocks can easily verify arguments received
      - Mocks can simulate timelines for CSP logic
  - Protocol testing support (helps prove network interactions are correct without running the full stack)

  ## Docs
  
  Make sure to check out the [Untangled Spec Docs](https://github.com/untangled-web/untangled-spec/blob/develop/docs/index.adoc#untangled-spec-docs) for up 
  to date documentation on features, setup and usage.
  
  ## Setting up

  If you look in `test/client/app` you'll see a few files. Only one of the four is a specification. The other three
  serve the following purposes:

  - `all_tests.cljs` : An entry point for CI testing from the command line.
  - `suite.cljs` : The entry point for browser test rendering.
  - `tests_to_run.cljs` : A file that does nothing more than require all of the specs. The test runners search for
  testing namespaces, so if you don't load them somewhere, they won't be found. Since there are two places tests
  run from (browser and CI) it makes sense to make this DRY.

  There is a `package.json` file for installing node packages to run CI tests.
  The `project.clj` includes various things to make all of this work:

  - The lein doo plugin, for running tests through karma *via* node (in Chrome).
  - A `:doo` section to configure the CI runner
  - A cljsbuild for test with figwheel true. This is the browser test build.
  - A cljsbuild for the CI tests output (automated-tests).
  - The lein `test-refresh` plugin, which will re-run server tests on save, and also can be configured with the
  spec renderer (see the `:test-refresh` section in the project file).

  ## Running server tests

  If your project is set up the same as this one, then you can write a specification like
  `test/server/app/server_spec.clj`, and the run all specs:

  ```
  lein test-refresh
  ```

  which will run the tests every time files are saved.

  ## Running client tests (during development)

  In these exercises you'll be working on read specs that are in `test/client/app` and `test/server/app`. If you followed
  the instructions for setting up this project locally, then you should be able to run the current sample tests in
  the browser: [http://localhost:3449/test.html](http://localhost:3449/test.html). If the tests are compiling, you
  may need to add: `-Dtest -Ddevguide` to the JVM arguments in your run configuration for figwheel.

  <img src=\"/img/figwheel-settings.png\">

  ## Anatomy of a specification

  The main macros are `specification`, `behavoir`, and `assertions`:

  ```
  (specification \"A Thing\"
     (behavior \"does something\"
        (assertions
           \"optional sub-clause\"
           form => expected-form
           form2 => expected-form2

           \"subclause\"
           form => expected-form)))
  ```

  The specification macro just outputs a `deftest`, so you are free to use `is`, `are`, etc. The `behavior` macro
  outputs additional events for the renderer to make an outline.

  ### Exercise 1

  Create a new specification `exercises-spec` in `test/client/app/exercises_spec.cljs`. Add a simple specification
  that tests if `2 * 2 = 4`. Remember to update `tests-to-run`. You can use the `ns` preamble from `sample-spec` to
  get the various requires correct.

  Solutions are in `exercises-solutions-spec`.

  ## Assertions

  Special arrows:

  - `=fn=>` : The right-hand side should be a boolean-returning lambda: `(fn [v] boolean)`
  - `=throws=>` : The right-hand side should be: `(ExceptionType optional-regex optional-predicate)`

  The `optional-regex` will match the message of the exception. The optional-predicate is a function that will be given
  the exception instance, and can make additional assertions about the exception.

  ### Exercise 2

  Create an additonal spec (in the same file as before) that uses the `=fn=>` arrow to check that an expression results
  in an even value.

  Solutions are in `exercises-solutions-spec`.

  ## Mocking

  The mocking system does a lot in a very small space. It can be invoked via the `provided` or `when-mocking` macro.
  The former requires a string and adds an outline section. The latter does not change the outline output.

  Mocking must be done in the context of a specification, and creates a scope for all sub-outlines. Generally
  you want to isolate mocking to a specific behavior:

  ```
  (specification \"Thing\"
    (behavior \"Does something\"
      (when-mocking
        (my-function arg1 arg2) => (do (assertions
                                          arg1 => 3
                                          arg2 => 5)
                                       true)

        (my-function 3 5))))
  ```

  Basically, you include triples (a form, arrow, form), followed by the code to execute (which will not have arrows).

  It is important to note that the mocking support does a bunch of verification at the end of your test:

  - It verifies that your functions are called the appropriate number of times (at least once is the default)
  - It uses the mocked functions in the order specified.
  - It captures the arguments in the symbols you provide (in this case arg1 and arg2). These
  are available for use in the RHS of the mock expression.
  - It returns whatever the RHS of the mock expression indicates
  - If assertions run in the RHS form, they will be honored (for test failures)

  So, the following mock script:

  ```
  (when-mocking
     (f a) =1x=> a
     (f a) =2x=> (+ 1 a)
     (g a b) => 32

     (assertions
       (+ (f 2) (f 2) (f 2) (g 1 2) (g 99 4) => 72))
  ```

  should pass. The first call to `f` returns the argument. The next two calls return the argument plus one.
  `g` can be called any amount (but at least once) and returns 32 each time.

  If you were to remove any call to `f` this test would fail with a verify error (`f` not invoked enough).

  ### Exercise 3

  Copy the following code to the top of your testing file:

  ```
  (defn sum-sq [a b] (+ (* a a) (* b b)))

  (defn replace-pairs [data]
    (map (fn [[a b]] (sum-sq a b)) data))
  ```

  Then add the following specification to the botton (`component` is an alias for `behavior`):

  ```
  (specification \"Exercise 3\"
    (component \"sum-sq\"
      (assertions \"Sums the squares of the two arguments\"
                  (sum-sq 1 1) => 2
                  (sum-sq 2 4) => 20
                  (sum-sq -3 -9) => 90))
    (component \"replace-pairs\"
      ; ... TODO ...
      ))
  ```

  A major aspect of good testing is isolation. When testing something you want the component parts to be
  tested, and then you want to isolate those components from the success/failure of the dependent (higher-level) parts.
  This ensures test specificity by ensuring that a minimal number of tests fails when something is wrong
  (the test should indicate the real, specific, problem).

  The supplied specification shows that `sum-sq` works (generative testing on that would be nice, too).

  Write the rest of the specification such that it will prove that `reduce-pairs` is correct *even if `sum-sq` is broken*
  (e.g. use mocking to make `sum-sq` return a well-known constant).

  Breaking `sum-sq` should *only* break the first component spec, whereas breaking `replace-pairs` should only break the
  second. Try it out!

  **Bonus**: Change your mocking to use =1x=> arrows for `sum-sq` to return different, but predictable, values for
  each call.

  Solutions are in `exercises-solutions-spec`.

  ## Using the original function (Spying)

  If you want to spy on a function, you can use mocking with a pre-capture of the function:

  ```
  (let [real-f f]
    (when-mocking
      (f a) => (do
                 (assertions
                     a =fn=> even?)
                 (real-f a))

      ...body...))
  ```

  ### Exercise 4

  Write more of an integration test for `replace-pairs` that both asserts (via mocking) that `sum-sq` is called,
  but *really* allows the original `sum-sq` to generate the real value. Add assertions that talk about how every result
  is positive, even when the pairs contain negatives. The macro `provided` is the same as `when-mocking`, but allows you
  to add a string to the output (and nest the outline a level deeper) to describe what the mocking is proving.

  Solutions are in `exercises-solutions-spec`.

  ## Timeline testing

  On occasion you'd like to mock things that use callbacks. Chains of callbacks can be a challenge to test, especially
  when you're trying to simulate timing issues.

  ```
  (def a (atom 0))

  (specification \"Some Thing\"
    (with-timeline
      (provided \"things happen in order\"
                (js/setTimeout f tm) =2x=> (async tm (f))

                (js/setTimeout
                  (fn []
                    (reset! a 1)
                    (js/setTimeout
                      (fn [] (reset! a 2)) 200)) 100)

                (tick 100)
                (is (= 1 @a))

                (tick 100)
                (is (= 1 @a))

                (tick 100)
                (is (= 2 @a))))
  ```

  In the above scripted test the `provided` (when-mocking with a label) is used to mock out `js/setTimeout`. By
  wrapping that provided in a `with-timeline` we gain the ability to use the `async` and `tick` macros (which must be
  pulled in as macros in the namespace). The former can be used on the RHS of a mock to indicate that the actual
  behavior should happen some number of milliseconds in the *simulated* future.

  So, this test says that when `setTimeout` is called we should simulate waiting however long that
  call requested, then we should run the captured function. Note that the `async` macro doesn't take a symbol to
  run, it instead wants you to supply a full form to run (so you can add in arguments, etc).

  Next this test does a nested `setTimeout`! This is perfectly fine. Calling the `tick` function advances the
  simulated clock. So, you can see we can watch the atom change over \"time\"!

  Note that you can schedule multiple things, and still return a value from the mock!

  ```
  (with-timeline
    (when-mocking
       (f a) => (do (async 200 (g)) (async 300 (h)) true)))
  ```

  the above indicates that when `f` is called it will schedule `(g)` to run 200ms from \"now\" and `(h)` to run
  300ms from \"now\". Then `f` will return `true`.

  ## Datomic Testing Support

  NOTE: Requires `untangled-datomic` in your project file.

  Untangled includes top-notch utilities for doing focused integration tests against an in-memory database. This
  allows you to quickly get your low-level database code correct without having to mess with a UI or full stack.

  ### Database fixtures

  The `with-db-fixture` macro creates an in-memory database with a schema of your Datomic migrations. It supports
  the following:

  - Running your migrations on an in-memory database localized to your test
  - Seeding that database via a function you supply (which just returns transaction data)
    - Returning a map from temporary ids in your seed data to real datomic ids in the resulting database

  ### Seed functions

  Seeding database can be a real pain, particularly when you need to create a graph of data. You have to generate
  tempids, etc. Then, when you want to find your seeded data you have to figure it out somehow!

  Untangled Datomic makes seeding very simple:

  Just use the `untangled.datomic.test-helpers/link-and-load-seed-data` function, which
  takes a connection and a list of legal Datomic transaction. The helper function lets you use `:datomic.id/X` in
  place of database IDs anywhere they make sense! This allows you to create any graph of data you need:

  ```
  (defn seed1 [connection]
    (helpers/link-and-load-seed-data connection
       [{:db/id :datomic.id/thing :thing/name \"Boo\" }
        {:db/id :datomic.id/list :list/things #{:datomic.id/thing}}]))

  (specification \"Boo\"
    (with-db-fixture db
       (behavior ...)
       :migrations \"namespace.of.migrations\"
       :seed-fn seed1))
  ```

  The fact that the seed is a function means you can compose your seed functions for quick, DRY test data generation.

  To get the remapped IDs, you can just pull the seed result from the fixture. The following function
  can be useful:

  ```
  (defn db-fixture-defs
    \"Given a db-fixture returns a map containing:
    `connection`: a connection to the fixture's db
    `get-id`: give it a temp-id from seeded data, and it will return the real id from seeded data\"
    [fixture]
    (let [connection (udb/get-connection fixture)
          tempid-map (:seed-result (udb/get-info fixture))
          get-id (partial get tempid-map)]
      {:connection connection
       :get-id     get-id}))
  ```

  which can be combined into it like this:

  ```
  (with-db-fixture fixture
     (let [{:keys [connection get-id]} (db-fixture-defs fixture)
           thing-id (get-id :datomic.id/thing)
           thing (d/entity (d/db connection) thing-id)]
           ; check out thing
       ))
  ```

  ### Exercise 5

  This exercise assumes you know something of the Datomic API. If you don't you might just want to read
  the solution in `db-testing-solutions-spec`.

  Edit `db-testing-spec` in the `server/app` package. Uncomment the specification, and write logic to prove the
  indicated TODO items.

  ```
  (specification \"My Seeded Users\"
    (with-db-fixture my-db
      (let [{:keys [connection get-id]} (db-fixture-defs my-db)
            info (udb/get-info my-db)
            db (d/db connection)
            get-user (fn [id] (some->> (get-id id) (d/entity db)))
            joe (get-user :datomic.id/joe)
            mary (get-user :datomic.id/mary)
            sam (get-user :datomic.id/sam)
            sally (get-user :datomic.id/sally)]
        (assertions
          \"Joe is married to Mary\"
          ; TODO
          \"Mary is friends with Sally\"
          ; TODO
          \"Joe is friends with Mary and Sam\"
          ; TODO
          \"Sam is 15 years old\"
          ; TODO
          ))
      :migrations \"app.sample-migrations\"
      :seed-fn user-seed))
  ```

  ## Protocol Testing

  Untangled client and server include utilities to help you test network protocols without a network. The idea is to
  prove (via a shared data file instead of a network) that the client is saying the right thing and the server
  will understand it and return a compatible result. Furthermore, it allows testing that the server response will
  result in your expected client app state.

  **NOTE:** This feature is somewhat experimental. The client-side tests are easy-to-write and confirm; however, the
  server side typically becomes somewhat complex (depending on your database seeding needs).

  In general, we place these tests and setup in a single `cljc` file. Here are the basic tests you want to run:

  - A test that joins your UI invocation with a real (Om Next) transaction. This proves your UI is saying what you expect.
  - A test that checks the *local mutation* of the transaction.
  - A test that checks that the mutation/query is transformed as expected for the *server request* (if at all)
  - A test that the *server transaction* does proper *tempid remapping* (if tempids are used)
  - A test that the *server responds* as expected (if it was a query)
  - A test that the server does the right *change to server-side state*.

  If all of these tests pass, then you have pretty strong proof that your overall dynamic interactions in the Untangled
  application works (optimistic updates, queries, and full stack mutations). This covers a lot of your code in a
  full-stack transactional way.

  ### Handling temporary IDs in protocol tests

  The protocol testing helpers you're about to see completely understand temporary IDs, and this make them really easy to use.

  The basic features are:

  - Anywhere you use a keyword namespaced to `:om.tempid/` (client or server side of the protocol), then
  the protocol helpers will translate them to real Om tempids during the testing
  (e.g. ensuring type checks for Om tempids will work in your real code).
  - Anywhere you use a keyword namespaced to `:datomic.id/` (client or server side of the protocol)
  the helpers will treat them correctly (e.g. on the server side it will join them up to seeded data correctly)

  So, in all of the tests you can use these namespaced keywords to simulate Om temporary and seeded data IDs.

  See `app.testing-devguide-solutions-spec` for a complete running example with proper `require`s.
  ")

(defcard-doc
  "### Testing that the UI executes the transaction you expect

  At the moment we're not doing direct UI testing. Instead we typically place any UI transact into a helper function
  that calls `transact!`. You could render your React components to a dom fragment and trigger DOM events with mocking
  in place, but in practice we find that the helper function approach is just less fuss, and it is good enough. You
  don't end up with absolute proof things are hooked up right, but it is close enough.

  So, let's say you've defined the following mutation:

  ```
  (defmethod m/mutate 'soln/add-user [{:keys [state ast] :as env} k {:keys [id name] :as params}]
       { :action (fn [] (swap! state assoc-in [:users/by-id id] {:db/id id :user/name name :user/age 1}))})
  ```

  and you've create the following UI helper function:

  ```
  (defn add-user [comp name]
       (om/transact! comp `[(soln/add-user ~{:id (om/tempid) :name name})]))
  ```

  The assumption is that somewhere in your UI you have:

  ```
  (dom/button #js {:onClick #(add-user ...)} \"Add User\")
  ```

  So, how do we test this?

  The first step is manual:

  ```
  ;; Define a data structure that can hold the bits of protocol
  (def add-user-protocol {:ui-tx            '[(soln/add-user {:id :om.tempid/new-user :name \"Orin\"})]})

  (behavior \"generates the correct ui transaction\"
     (when-mocking
        (om/tempid) => :om.tempid/new-user
        (om/transact! c tx) => (is (= tx (-> add-user-protocol :ui-tx)))

        (add-user :some-component \"Orin\")))
  ```

  Now we've shown that the UI generates the expected transaction. Our next step is to see that this tx has the proper
  client-side effect.

  ### Testing that the UI Optimistic Update is correct

  Let's ensure that the mutation does the correct optimistic update. Since this could
  involve changing all sorts of things in the state map at arbitrary nesting levels, and should technically run as
  a partial integration test through the internal parser, Untangled provides a nice helper function
  that does all of this for you.

  This is made possible by the fact that mutations are globally added to a single multimethod, and all of the plumbing
  is built-in. Thus, the only thing you have to say is what should happen to the app state! We do this in the shared
  data structure at the top of the CLJC file.

  The data for an optimistic update is stored as a delta, formatted as follows:

  ```
  {:optimistic-delta { [key-path-to-data] expected-value }
   ...other test data... }
  ```

  So, for example to check that a map with `:id 1` has appeared in database table `:table` at key `1` you'd use:

  ```
  { :optimistic-delta { [:table 1 :id] 1 } }
  ```

  You can, of course, check the whole object (instead of just the ID), but often spot checking is sufficient.

  To check the optimistic delta:

  - Make **sure** you've required the namespace that defines the mutation under test! If you don't do this, your
  mutation may not be installed, and will fail.
  - Require `untangled.client.protocol-support` (perhaps as `ps`)
  - Call `(ps/check-optimistic-delta protocol-def-map)` within a specification

  So, for our example from the previous section, which was:

  ```
  (defmethod m/mutate 'soln/add-user [{:keys [state ast] :as env} k {:keys [id name] :as params}]
       { :action (fn [] (swap! state assoc-in [:users/by-id id] {:db/id id :user/name name :user/age 1}))})
  ```

  We'd add the following to our protocol:

  ```
  (def add-user-protocol
    {:ui-tx            '[(soln/add-user {:id :om.tempid/new-user :name \"Orin\"})]

     :initial-ui-state {:users/by-id {}} ; <-- starting app state

     ; expected (spot-checks) of changes made to local app state by the mutation:
     :optimistic-delta {[:users/by-id :om.tempid/new-user :user/name] \"Orin\"
                        [:users/by-id :om.tempid/new-user :user/age]  1}
    })
  ```

  and our test is now just:

  ```
  (specification \"Adding a user\"
     (behavior \"generates the correct ui transaction\"
        (when-mocking
           (om/tempid) => :om.tempid/new-user
           (om/transact! c tx) => (is (= tx (-> add-user-protocol :ui-tx)))

           (add-user :some-component \"Orin\")))
     (ps/check-optimistic-update add-user-protocol)) ;; <-- added this line
  ```

  The `check-optimistic-delta` uses the `ui-tx` entry to know what to attempt, and the mutations are already
  installed. So, it makes up an app state (which is empty, or `initial-ui-state` if supplied).

  ### Testing how a mutation talks to the server

  Mutations can trigger a remote mutation (which can modify the original UI transaction). For example, say
  `(add-user)` is a perfect thing to say to the UI, but when sending it to the server you must add in
  a parameter (e.g. a default user's age).

  The `:remote` of a mutation can return `true` (to send the UI mutation) or a modified mutation AST to
  send a modified mutation. Parameters are the easiest thing to morph:

  ```
  (defmethod m/mutate 'soln/add-user [{:keys [state ast] :as env} k {:keys [id name] :as params}]
       {:remote (assoc ast :params (assoc params :age 1)) ; <-- add :age to the parameters
        :action (fn [] (swap! state assoc-in [:users/by-id id] {:db/id id :user/name name :user/age 1}))})
  ```

  To add this to the tests, we add one more entry to our protocol and call `ps/check-server-tx`:

  ```
  (def add-user-protocol
    {:ui-tx            '[(soln/add-user {:id :om.tempid/new-user :name \"Orin\"})]

     ; What should go across the network:
     :server-tx        '[(soln/add-user {:id :om.tempid/new-user :name \"Orin\" :age 1})]

     ; ... as before
    })
  ```

  ```
  (specification \"Adding a user\"
     (behavior \"generates the correct ui transaction\"
        (when-mocking
           (om/tempid) => :om.tempid/new-user
           (om/transact! c tx) => (is (= tx (-> add-user-protocol :ui-tx)))

           (add-user :some-component \"Orin\")))
     (ps/check-optimistic-update add-user-protocol)
     (ps/check-server-tx add-user-protocol)) ;; <-- added this line
  ```

  ### Testing the the server transaction does the correct thing on the server (Only supports Datomic)

  Now we've verified that the UI will invoke the right mutation, the mutation will do the correct optimistic update,
  the mutation will properly modify and generate a server transaction. We've reached the plumbing (network), and we'll
  pretend that it works (since it is provided for you).

  Once it is on the network, we can assume it reached the server (these are happy-path tests). Now you're interested
  in proving that the server code works.

  Untangled can help here too!

  Only one function call is needed (with some setup): `ps/check-response-to-client`. It supports checking:

  - That the `:server-tx` runs without error
  - That `:response` in the protocol data matches the real response of the `:server-tx`
  - That Om tempids are remapped to real datomic IDs in the `:response`
  - (optional) post-processing checks where you can examine the state of the database/system

  The setup allows you to seed an in-memory test database for the transaction to run against.

  **NOTE:** The documentation here is incomplete, as we're still trying to find a simpler way to help you set up
  these tests. All of the helper utilities exist to do it, but they are not easy enough to use.

  ### Testing that the server response is properly understood by the client

  Now that you've proven the server part works, you'd shown that `:response` in the protocol data is correct. You
  can now use that to verify the client will do the correct thing with it.

  This is another cljs test, supported by `check-response-from-server`. It is very similar to the optimistic
  update test, and simply requires these keys in the protocol data:

  `response`: the exact data the server is supposed to send back to the client (which you proved with a server test)
  `pre-response-state`: normalized app state prior to receiving `response`
  `server-tx`: the transaction originally sent to the server, yielding `response` (which you proved was what the UI sends)
  `merge-delta`: the delta between `pre-response-state` and its integration with `response`. Same format as optimistic-delta.

  The call to `check-response-from-server` sets up the Untangled internals with the given `pre-response-state`, merges
  the response with the normal merge mechanisms (which requires the `server-tx` for normalization), and then
  verifies your `merge-delta`.

  You can use this method to test simple query response normalization, but in that case you must use a `server-tx` that
  comes from UI components (or normalization won't work).

  ")


