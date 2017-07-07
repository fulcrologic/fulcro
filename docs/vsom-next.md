# Untangled vs. Stock Om Next

If you've tried stock Om Next, you probably found out pretty quickly that there are a lot of things you have
to figure out, plug in, and set up before you actually have something working as a full-stack app with
complete features. The primary difference is that Untangled makes a lot of these choices for you so that you
can work on what you care about: your features!

## TL;DR

The gist is that Untangled supplies all of the bits and pieces you need to get going. This
gives Untangled some very nice properties that Stock Om Next does not:

- Easier local reasoning (no hand-building initial UI state or composing parser bits)
- Refactoring UI is much easier. Just move the component and re-compose locally in the UI.
- Running any *sub-portion* of you UI **as an application** in devcards is trivial. No need to build a pared-down parser. This allows much easier separation of development tasks.
- Data loading as a first-class citizen (e.g. `(load this :people Person {:target [:person-list/by-id 3 :people]})`)
- Predefined mechanisms for mutation and read that make 3rd-party libraries of components more tractable to build.
- A well-defined story around network interaction and data merging.
- A simplified model for generating UI from application state (no custom parser).

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

### Simpler Development Process

Adopting the model above leads to much easier development.

#### Implementing something new

**Stock Om Next:**

1. Write the UI
2. Compose the UI
3. Code logic to generate the data feed:
    - Find the correct place in your parsing emitters to hook the new bits
    - Write logic to generate the data on-the-fly when requested
    - Deal with caching (memoization) when performance gets bad
    - (possibly) Remember to properly co-locate the remote behaviors of that data.
4. Write mutations that evolve the core data on which the logic depends

**Untangled:**

1. Write the UI
2. Add InitialAppState to the component
3. Compose in the component
4. Write mutations (you need those anyhow) that directly evolve the model to "look right"

The remote story is separate, and not complected with the local data reads at all. Notice
that in Untangled your view updates are directly linked to an action of the UI (mutation)
instead of logic that might have performance implications during rendering. The (sort of)
down-side is that Untangled is essentially asking you to always cache your calculation for rendering.
This means your data model is slightly larger than what you might have in Om Next, but notice
it also solves the potential performance bottleneck: you only re-calculate the data for
a view when it changes, and those events are well-defined.

#### Implementing Things in Isolation

This is perhaps the very biggest positive result of Untangled making
the decisions it has made: elements of your program become much more
isolated and modular!

**Stock Om Next**

Unfortunately, your parser is usually tied to the UI structure you invent (you have to expect
the UI to want to walk some particular graph). This means that if you want to pull out
some "screen" and dropp it in a devcard, then you must customize the parser in order to
support it, figure out what app state you need, compose it all together and hope that the
devcard experience actually represents how it will behave within the real application.
Since the remoting and processing of query roots is also tied to this concern, it quickly
becomes rather cumbersome and difficult. The same thing happens when two developers are working
on the same application. You both end up working primarily on the parser, resulting in
a higher likelihood of code conflicts on merge.

**Untangled**

The predefined data model means there is no parser. All components have an ident, their initial
state composes locally, and loads are not tied to UI structure. If you want to put a screen
in a devcard you just compose it into a new Root and drop it in place! The only thing
that (typically) fails to work is navigation to UI stuff that you didn't bring along!

There is a video on YouTube that gives you a taste for [this in action](https://youtu.be/uxI2XVgdDBU?list=PLVi9lDx-4C_T_gsmBQ_2gztvk6h_Usw6R).

Code conflicts become a lot less frequent as well since co-development is now almost completely isolated.

#### Here is the process of debugging something that isn't getting data (for some reason):

**Stock Om Next:**

1. Examine the component and make sure the query is right
2. Go find your initial app state (which is a hand-written blob of data), and see if it looks right
3. Look at your (possibly custom) database to see that the data is in the right place
4. Debug your recursive parsing algorithm to see why the data isn't getting there

**Untangled:**

1. Examine the component for query (and possibly initial state..but that is co-located and composed **locally** on the component)
2. Examine the current data graph (usually one entry in the table for that component)

In practice this is much much simpler and easier.

#### Here is the process of refactoring your UI (moving UI from one place to another)

**Both:**

1. Move the component and re-compose the query into the parent

**Stock Om Next:**

2. Fix your initial app state data structure
3. Fix your parser/read emitters
4. Possibly correct the remoting behavior coded in that part of the parser.

**Untangled:**

2. Re-compose InitialAppState into parent (like query)
(remoting is separate, and there is no custom parser)

## Remote Interaction

Om Next *defines* the model of how to unify the local and remote interactions to the same data language (query + mutations)
in order to make a nice data-driven application, but much of the *implementation* is up to you.

When doing networking: stock Om Next has you return remote information
from your parser emitters. This joins the logic of network
interaction with the local read processing. At first this sounds good: the logic for triggering
the correct remote query is with the logic for locally satisfying it.

However, you must invent a lot here in order to get this all to work right:

1. Get the parser right
2. Augment the data merge functions to make sure the data merges the way you expect.
3. Write the networking code.
4. Augment the networking code to strip/replace the UI-only parts of the root-based UI query (e.g. `process-roots`)
5. Worry about stripping ui-only query bits from the query you send to the server.
6. Define how you will handle errors from the server.
7. Define and implement some kind of "loading" marker scheme
8. Define the semantics of network processing order: i.e. should your client allow more than one mutation to go at once? If so
how will you deal with out-of-order execution on the server? If both reads and writes are involved, should there be
an explicit ordering?

Untangled does all of this for you, and more. The big realization when designing Untangled was this:

In Om Next, triggering loads happens under two circumstances:

1. On initial load (your parser can clearly see that data is missing)
2. After some mutation that places some kind of marker in app state that your parser can understand means "load X"

The first has a clear point in time, and need not be related to parsing, and
the second also has a clear point in time (i.e. a mutation happened due to a user action, timeout, etc).

Furthermore, you typically want to put some kind of "busy" indicator to keep track of
what is loading (and render that), and the hack in some kind of state change code to
put markers in app state to indicate when there is an error.

So, instead of having you write the logic of load into the places that are trying
to parse the query, Untangled adds a higher-level `load` function to address all of these.
Technically it is just a wrapper around a built-in mutation that adds a query
to a network processing queue (in app state) and triggers remote evaluation. You still use
UI-based queries, but you no longer have to worry about how it is tangled up in the UI (you get
to use a relative location rather than a query from the root).

A [whiteboard discussion](https://youtu.be/mT4jJHf929Q?list=PLVi9lDx-4C_T_gsmBQ_2gztvk6h_Usw6R) of this can be seen on YouTube.

1. There is a started callback function that is triggered on mount. Initial loads can be explicitly triggered.
2. The ability to explicitly trigger loads means user-level events, timeouts, etc. can start all other loads.

Untangled also defines more advanced merging behavior, auto-stripping of properties that the server should
not see, sequential network processing (with parallel as an explicit option), websockets, error handling,
load progress markers, etc.

## Other Features

Untangled attempts to include solutions for most of the common problems that business webapps are
trying to solve: testing, i18n, and advanced system for generating full-stack forms, CSS,
file uploads, standing up a server.

All of these are things you have to "plug in" with
stock Om Next. While Untangled doesn't lock you in to one solution for these, it does provide an
included option that makes rapid application development possible.


