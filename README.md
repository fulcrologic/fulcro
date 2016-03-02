# Untangled

An opinionated web framework with fewer sharp edges.

Untangled provides the following features for creating single-page
web applications:

- Simple, local reasoning everywhere.
   - Rarely need core.async! Much less complexity.
- Immutable data, with *no need* for embedded/hidden local state
- Interfacing to React via Quiescent for a *referentially transparent* rendering model.
- A data model similar to Om
- A custom component event model for localized component communication
- Unit testing tools, including:
   - Event simulation
   - DOM analysis/checkers
   - Async timeline simulation (via our companion testing library)
   - An in-browser test runner that requires NO external tools (including auto-running)
   - Any number of different browsers running all tests automatically simply by sending the browser to a web page.
- Application Undo/Redo 
   - The ability to mark "undoable" operations to prevent undo over things like POST.
   - The ability to "hook" undo functions to make "undoable" operations (like POST) undoable!
- "Support ticketing" that provides your support team and developers 
  with accurate information about what went wrong when someone
  reports a problem.

## The Problem

Other frameworks (for Clojurescript) have varying degrees of complexity. Om
has some wonderful ideas, but the overuse of protocols, use of core.async,
and tendency to allow for embedded/hidden state left us looking for more.

Reagent throws caution to the wind (in an FP sense), but gives developers
something that feels more like Angular. Attractive, yet the complexity that
mutation brings to the table is a non-starter for teams looking to 
stick with an FP approach.

The Quiescent rendering library for ClojureScript gives you the ability
to write pure functions that can be efficiently used to render your
application's user interface, and is a very thin layer of code that
is largely interested in making React hyper-fast under the assumption
that you'd like to treat rendering as a pure function.
makes writing the UI itself very simple (and unit-testable); however,
this leaves you to invent everything else.

The main problems that you need to solve when writing a real webapp
with Quiescent are:

- Where do I put my application data?
- How do I model "transient state", which we are encouraged NOT to
  hide in the local UI code?
- How do I hook up event handlers without introducing incidental complexity?
- When do I trigger re-renders?

If you read the TODOMVC example for quiescent, you'll see that using
core.async and a global set of handlers is one possible approach, and
indeed that code is very clean and easy to understand; however, if you
were to take that approach with a larger application you very quickly
run into some concerns:

The structure of the application data is encoded throughout the recursive descent of the UI itself
See, for example, [this line](https://github.com/levand/todomvc/blob/bdb6b8c0bb51bb8afa8430292634c270e7d77f1d/architecture-examples/quiescent/src/todomvc_quiescent/render.cljs#L45):

     (let [completed (count (filter :completed (:items app))) ...

The example has been coded so that each _local_ component has to know
the structure of the _global_ data.

Additionally, event handlers are easy to hook up, but suffer from
similar problems: you're either encouraged to do direct `swap!`s on
global state, or send messages to an API via `core.async` channels, or
invent you own. The lack of an opinion on where to put the state
leaves you in a position that can cause your collaborators to "do the
wrong thing" and tie implementation in a manner that complects your UI
with your data model.

## A Solution

This library includes a data modelling framework that attempts to
honor the pure FP intentions of Quiescent while encouraging clean
**local reasoning** wherever possible. It builds on ideas taken
(and simplified) from Om.

Specifically:

- Eliminate **direct reliance** on globals anywhere in your UI
  implementation. Global _definition_ is fine.
- Provide a strong opinion about the data modelling that allows you to
  concentrate on building your webapp without having to design that
model and the associated conventions for using it.
- Provide the ability to write the **UI** in a manner that *promotes
  local reasoning and referential transparency* of the UI rendering
- Support writing the *data model* of components in a manner that
  allows **completely local reasoning and easy referential
transparency**.
    - Support transient _and_ persistent data
    - Support the ability for local components to *create* new
      (sub)state without having to know the specifics of the entire
data model. For example, a "TODO" list component needs to create TODO
items.
- Enable event handler hook-ups that need know **nothing** about the
  specifics of data storage (location, name, or content), just the
abstract API of the local component's model.
- Support solid composition and editing:
  - Multiple sub-applications on the same page.
  - (Re)use components in arbitrary locations within the webapp
    without worrying about data locality.
  - Move existing components in the UI without needing to affect any
    other layer

# Approach

## Modelling Application State

Your first step is modelling the state of a component. Your model should:

- Be stored in a map
- Include transient and persistent state
- Include functions for every possible operation on the component (mostly referentially transparent)
  - Any functions that are not referentially transparent will be things like triggers of AJAX calls
  - All other functions should take the component state (as the _last_ argument) and return the new state

So, a calendar component might look like this:


    (defn initial-calendar
      ([id] (initial-calendar id 1 1 2015))
      ([id month day year]
       {
        :id               id
        :month            month
        :day              day
        :year             year
        :overlay-visible? false
        :items            [{:text "item one"}]
        })
      )
    
    ;; Pure functions for using/updating calendar 
    (defn displayed-date [calendar] (str (:month calendar) "/" (:day calendar) "/" (:year calendar)))
     
    (defn set-date "set the date using a map with keys :month :day :year" [new-dt calendar] (merge calendar new-dt))
     
    (defn next-month "retuns a new calendar one month in the future from the argument" [calendar]
      (let [this-month (:month calendar)
            next-month (if (= this-month 12) 1 (+ 1 this-month) 12)
            this-year (:year calendar)
            year (if (= 1 next-month) (+ 1 this-year) this-year)
            ]
        (assoc calendar :month next-month :year year))
      )
     
    (defn toggle-calendar-overlay "toggle visiblilty of the days of the month" [calendar] (update calendar :overlay-visible? not))

This model is nicely unit-testable, easy to reason about, and completely localized.

## Storing Application State

Your next step to to store this state somewhere.

First, we make the following assertions about application state:

- It has to go somewhere
- It needs to be easily identifiable when we find it (when we see that data, we have a good idea of what it is used for)
- We need to be able to look it up
- It may include items that have *nothing* to do with the UI
- It will often have *inherent* complexity (just in quantity alone)

Next, we realize that we can do nothing about the last item, we can simply attempt to add little or no
_incidental_ complexity [(thanks for the clarity Rich!)](http://www.infoq.com/presentations/Simple-Made-Easy).

So, it is time we formed an opinion: This library, like Om, requires that you store application state in a map within an atom. 
This atom can be created in a let, def, or whatever. For figwheel, it is convenient to use a defonce:

     (defonce app-state (atom { ... }))

You may have as many of these as you have root-level renderings in your application. For example, you can definitely
split your UI (on a single page) into sub-components, each of which has it's own state. This is useful for things
that don't need to interop. For example, the navigation/router of your single-page webapp might work just fine
as an isolated sub-component with it's own state. There is also nothing that says you cannot define crossover APIs
(e.g. event systems, core.async channels, whatever) that allow one sub-component to communicate with another, though
in the general case you will not need that complexity.

Within this state, you may structure it as you like with the following conventions for any data that will have direct
use in the UI:

A component's data in the state _must_ be a map that:
- _Should_ include any key/value pairs to model the local state of that component, including transient state (e.g.
`overlay-visible?`)
- _May_ include data for sub-components that are "managed" by this component (e.g. are themselves plain Quiescent/React components,
not stateful ones). In other 
words, you may define what "local reasoning" means for your component. There may be tight clusters of components 
(e.g. an input field component within a form) that wish to have some localized coupling and don't need this library's
direct support.


     ; application state namespace
     (defonce app-state
       (atom 
            {
              ; two things that are not component state...app-specific purpose. Need not be maps
              :VERSION 1.0
              :some-other-tracking-thing [] 
              ; not a 'component' itself...just part of the organization of state. needs no :id
              :visualizations { 
                                 :main-report { ; component
                                    :start-date (cal/make-calendar) ; sub-components
                                    :end-date (cal/make-calendar)
                                    :table-data { :data [] }
                                 }
                              }
            }))
            
Note that the application state itself is as simple as it _can_ be. Component model "constructor" functions can
be used to simplify the initialization of this state, and an application start-up function could even set defaults
via a simple `swap!` on this state. AJAX calls (e.g. to fill out the table's data vector) are similarly trivial to implement.

We can summarize the most important point as: EACH component in the UI has a unique ID in a map, and you are required to assign it.
If two different date pickers are there, then each will have a unique ID. If you have 10 pop-up panels that each have 2 date
pickers, then there will be 20 date pickers in your application state, each with a unique ID.

## Lists of objects

You can also use vectors of objects in your application state. These support associative access, but in order for them
to work properly with this library (and optimized React rendering) each such item _must_ be a map that in turn _must_
have a unique ID of some sort (e.g. random uuid, server-side id, etc.).

We have a number of issues to solve, and the above requirement solve them. They are:

1. React prefers to know a unique ID for lists of things, so it can optimize rendering.
2. We need to be able to locate a distinct item in a potentially changing list (e.g. you might reorder items). We need
a way to correlate the real item in the list with the new position, or event handlers will do things like look up the
wrong state.

## Writing the UI Component

Next we want to write the actual UI code of the component, and we want to do it in a way that preserves local reasoning,
decoupling from global state, and automatic re-rendering when needed (thanks to Quiescent). 

You define components using the `defscomponent` macro:

    (ns my-app.calendar
      (:require [untangled.component :as c]
                [untangled.state :as state]
                [quiescent.core :as q]
                [my-app.model.calendar :as cal]
                [quiescent.dom :as d]))
                
    (c/defscomponent Calendar
                     "A Calendar" ;; A name/description, as in quiescent
                     :on-mount (fn [&rest] (.log js/console "Hello"))  ;; as in quiescent
                     [calendar-data context] ;; required signature, you pick the names
                    (let [op (state/op-builder context)
                          overlay-visible? (:overlay-visible? calendar-data)
                          move-to-next-month (op cal/next-month)
                          toggle-days (op cal/toggle-calendar-overlay)
                          set-date-builder (fn [dt] (op (partial cal/set-date dt)))
                          ]
                      (d/div (d/span { :onClick toggle-days } ()
                         ...
                         (d/button {:onClick move-to-next-month } "Next Month")
                      ) ...))

There are a number of things to take note of:

- The argument list to your body of the component has two arguments. They will be:
  - The state of your component (you need not worry where it is stored)
  - The rendering context. This context is used for generating event handlers and for rendering sub-components.

Now, you can write the rendering itself based on your localized understanding of the component model. E.g. you
simply access the first argument as a "calendar" model:

       (let [overlay-visible? (:overlay-visible? calendar-data)

Also note that all of the UI operations need know absolutely nothing about the location of the data, how it gets updated
or even its format (accessor functions for things like overlay-visible? could give you this property for reads as well).

The `untangled.state/op-builder` function is the real work-horse and key to this entire library's simplicity. This
function takes the current rendering context and produces a callback-builder (commonly referred to in this doc and 
examples as `op`, short for "operation-on-the-current-state").  This is a higer-order function that is meant to preserve
everything about local reasoning on your component. It requires a function that accepts the current state of the 
component and returns an updated state (e.g. calendar/next-month takes a current calendar and returns a new one representing
next month). `op` itself _returns_ a function that can cause this side-effect on the application's state (which can then
be used as a callback function for async operations, events, etc.). No need for core.async or other complications! You
can reason about things locally (a function than takes/returns a calendar) and an abstraction (a higher order function 
that can make a change to my component's state). Technically your function can also have side-effects (though purity is 
meant to be the goal). 

Since the only thing the callback-builder
is going to pass to your function is the component's state, one wonders how to do many of the useful tasks, like update
the state of an input field based on an event. Simple, since we're doing FP: Partial application. (See `set-date-builder`
in the example above). If you choose to use partial application (instead of anonymous functions) then we recommend
you write your multi-arg pure functions of component state such that the component itself is the last arg.

     (defn set-date [new-dt calendar] ...)
     
     (let [goto-today (op (partial set-date today))] 
        (d/button { :onClick goto-today } "Today")
     )

When creating the UI elements of sub-components (e.g. for days in the calendar), you can make event handlers for those
day selections by constructing new functions for each day:

       (let [set-date-to (fn [dt] (op (partial cal/set-date dt)))]

       ; assume 'days' is the vector: [ {:month 1 :day 1 :year 2015 } {:month 1 :day 2 :year 2015 } ... ]
       (map (fn [d] (d/span { :onClick (set-date-to d) } (str (:day d))) days)

## Using Your Component

You component body (above) _receives_ parameters passed by the internals of this library. It is very important for
you to note that *you do not pass* these arguments when you use it. *This is a significant break from
quiescent, React, and Om*.

Instead, `defscomponent` def's a function that can create a React component based on your template (above), the 
key of your component in the application state, and the rendering context. Thus, you create the instances of your component with:

     (Calendar key context)

At first this may sound like you are not preserving local reasoning, but remember that this render will be invoked
in the context of the component that owns the state of that sub-component (even though it used that component's
constructor function to build that state). For example:

     (defn make-visualization []
     { :visualization {
           :start-date (make-calendar)
           :end-date (make-calendar) })
       
The renderer for Visualization knows (because it's constructor defined the map) that there are calendars at :start-date
and :end-date. So even if you embed Visualization somewhere deep, the renderer for Visualization still knows where 
the sub-state is (even though it does not know where it, itself, is).
 
Think of this just like you do a call stack in normal programming. The stack is a global data structure, and the 
"stack pointer" is a behind-the-scenes mechanism for tracking where in the stack you're code is running. Rendering
`context` is this stack pointer.

## Components without state

You may use quiescent (or raw React) to create any components of your UI. You may also simply write functions
that output groupings of other components. There is nothing to say that all of your UI elements have to be modeled
with `defscomponent` and explicit state.
 
This preserves simplicity, rendering efficiency, and local(ized) reasoning. You can also very simply use the `callback-builder`
(op) to build up operations that can be passed as your 'hook-up logic'
through to these raw components so they can still act on application state. 

Remember that React rules, however, still apply: Components don't re-render unless their state changes. So if you do
raw rendering within some sub-tree of the UI, be sure that tracked state changes will cause appropriate re-rendering
when needed.
