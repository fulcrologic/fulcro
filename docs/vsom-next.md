# Untangled vs. Stock Om Next

If you've tried stock Om Next, you probably found out pretty quickly that there are a lot of things you have
to figure out, plug in, and set up before you actually have something working as a full-stack app with
complete features. The primary difference is that Untangled makes a lot of these choices for you so that you
can work on what you care about: your features!

## The Database

Om Next has a suggested "default" client-side database format. Untangled adopts this as the *only* choice for
the client-side database. This has the following trade-offs:

- It is possible to pre-write the code that pulls the data from the database for the UI. No need
to write that yourself.
- Libraries can be more easily created. With stock Om Next there is no real way for developers to provide
components with queries, because there is no way for that developer to know how to write
that component's mutations! There is no known database on the client for them to target.
- The default format is about as fast as you can get. Other choices like Datascript are more powerful, but
at a query cost that might be too high for your UI refresh rates to look good. Also, the code to write
to interact with something like Datascript is a heck of a lot harder than `get-in`, `update-in`, or `assoc-in`

The primary disadvantage is that you don't have full support of all of the query langauge features
in the client.
Om Next has the general idea that since you're writing the guts of query engine, you can "interpret" the query on the fly.
This is a great idea, but the Untangled philosophy is that most people don't want to thing about feeding their
UI by having to write query parsing "emitters". It decouples things in a way that is powerful, but overkill for
most applications.

Instead, Untangled has you explicitly represent what you want from a query through the data model itself.

Om Next:

```
Query -> Parser -> Data Tree -> UI
           |
         reads (you implement)
```

Untangled
```
Query -> db->tree -> UI
```

Thus, in Untangled you essentially have the mutations update the data graph so that the query will be answered
in the "correct" way.

While this sounds less powerful (it technically is), it makes general development much simpler.

Here is the process of implementing something new in stock Om Next:

1. Write the UI
2. Compose the UI
3. Find the correct place in your parsing emitters to hook the new UI bits, and remember to properly co-locate the remote behaviors of that data.

Here is how you do it in Untangled:

1. Write the UI
2. Add InitialAppState to the component
3. Compose in the component

Done. The remote story is separate, and not complected with the local data reads at all.

Here is the process of debugging something that isn't getting data:

Stock Om Next

1. Examine the component and make sure the query is right
2. Go find your initial app state (which is a hand-written blob of data), and see if it looks right
3. Look at your (possibly custom) database to see that the data is in the right place
4. Debug your recursive parsing algorithm to see why the data isn't getting there

Untangled:

1. Examine the component for query (and possibly initial state..co-located, and composed local to the component)
2. Examine the current data graph

Here is the process of refactoring your UI (moving UI from one place to another)

Both:

1. Move the component and re-compose the query into the parent

Stock Om Next
2. Fix your initial app state data structure
3. Fix your parser/read emitters
4. Possibly correct the remoting behavior coded in that part of the parser.

Untangled:
2. Re-compose InitialAppState into parent (like query)
(remoting is separate, and there is no custom parser)

## Remote Interaction

Om Next unifies the local and remote interactions to the same data language (query + mutations). Stock
Om Next has you return remote information from your parser emitters. This complects the logic of network
interaction with the local read processing. You are left to completely invent how you will
fetch data from the server. In order to get this all to work right you must:

1. Get the parser right
2. Augment the merge functions to make sure the data merges the way you expect
3. Write the networking code
4. Augment the networking code to strip/replace the UI-only parts of the root-based UI query
5. Worry about stripping ui-only query bits from the query you send to the server
6. Define how you will handle errors from the server
7. Define the semantics of network processing order: should your client allow more than one mutation to go at once? If so
how will you deal with out-of-order execution on the server?
8. Others...

Untangled does all of this for you. The big realization we had when designing Untangled was this:

In Om Next, triggering loads happens under two circumstances:

1. On initial load (your parser can clearly see that data is missing)
2. After some mutation that places some kind of marker in app state that your parser can understand means "load X"

So, Untangled adds higher-level functions to address these:

The primary function is `load`, which is a wrapper around a built-in mutation that adds a query
to a network processing queue (in app state) and triggers remote evaluation. The guts of Untangled
merge the pending queries and send them. Since the queries are explicit, there is no need
for all of the UI stripping, rewriting, etc. You can load exactly what you want, when you want.

1. There is a started callback function that is triggered on mount. Initial loads can be explicitly triggered.
2. The ability to explicitly trigger loads means user-level events, timeouts, etc. can start all other loads.

Untangled also defines more advanced merging behavior, auto-stripping of properties that the server should
not see, sequential network processing (with parallel as an explicit option), websockets, error handling,
load progress markers, etc.

## Other Features

Untangled attempts to include solutions for most of the common problems that business webapps are
trying to solve: testing, i18n, standing up a server. All of these are things you have to "plug in" with
stock Om Next. Untangled doesn't lock you in to one solution for these, but having a well-designed
option that is included makes rapid application development possible.


