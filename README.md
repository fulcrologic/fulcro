# Quiescent Model

An opinionated data model to use with Quiescent that encourages a pure FP approach with local reasoning about all
of the significant components, and minimal coupling.

## tl;dr

Here is a short example that shows the basic usage of this library:

    TODO: Link to example project

## The Problem

The Quiescent rendering library for ClojureScript give you the ability to write pure functions that can be efficiently
used to render your application's user interface. It forms no opinion of how you store that data, and makes writing
the UI itself very simple (and unit-testable); however, this leaves you to invent an organization of layout for your
data (both persistent and transient), and can lead to a lot of unintentional complexity in your application that
requires some careful design.

The main problems that you need to solve when writing a real webapp with Quiescent are:

- Where do I put my application data?
- How do I model "transient state", which we are encouraged NOT to hide in the local UI code?
- How do I hook up event handlers without introducing incidental complexity?
- When do I trigger re-renders?

If you read the TODOMVC example for quiescent, you'll see that using core.async and a global set of handlers is one
possible approach, and indeed that code is very clean and easy to understand; however, if you were to take that
approach with a larger application you very quickly run into some concerns:

The structure of the application data is encoded throughout the recursive descent of the UI itself
See, for example, [this line](https://github.com/levand/todomvc/blob/bdb6b8c0bb51bb8afa8430292634c270e7d77f1d/architecture-examples/quiescent/src/todomvc_quiescent/render.cljs#L45):

     (let [completed (count (filter :completed (:items app))) ...

The example has been coded so that each _local_ component has to know the structure of the _global_ data.

Additionally, event handlers are easy to hook up, but suffer from similar problems: you're either encouraged to do
direct `swap!`s on global state, or send messages to an API via core.async channels, or invent you own. The lack of
an opinion on where to put the state leaves you in a position that can cause your collaborators to "do the wrong thing"
and tie implementation in a manner that complects your UI with your data model.

## A Solution

This library is a data model framework that attempts to honor the pure FP intentions of Quiescent while encouraging
clean **local reasoning** wherever possible.

Specifically:

- Eliminate **direct reliance** on globals anywhere in your UI implementation. Global _definition_ is fine (and useful
with figwheel), but optional.
- Provide a strong opinion about the data modelling that allows you to concentrate on building your webapp without
having to design that model and the associated conventions for using it.
- Provide the ability to write the **UI** in a manner that *promotes local reasoning and referential transparency* of the UI rendering
- Support writing the *data model* of components in a manner that allows **completely local reasoning and easy referential
transparency**.
    - Support transient _and_ persistent data
    - Support the ability for local components to *create* new (sub)state without having to know the specifics of the
    data model. For example, a "TODO" list component needs to create TODO Items.
- Enable event handler hook-ups that need know **nothing** about the specifics of data storage (location, name, or content),
 just the abstract API of the local component's model.
- Support solid composition and editing:
  - Multiple sub-applications on the same page.
  - (Re)use components in arbitrary locations within the webapp without worrying about data locality.
  - Move existing components in the UI without needing to affect any other layer

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

So, it is time we formed an opinion: This library requires that you store application state in a map within an atom. 
This atom can be created in a let, def, or whatever. For figwheel, it is convenient to use a defonce:

     (defonce app-state (atom { ... }))

You may have as many of these as you have root-level renderings in your application. For example, you can definitely
split your UI (on a single page) into sub-components, each of which has it's own state. This is useful for things
that don't need to interop. For example, the navigation/router of your single-page webapp might work just fine
as an isolated sub-component with it's own state. There is also nothing that says you cannot define crossover APIs
(e.g. event systems, core.async channels, whatever) that allow one sub-component to communicate with another.

Within this state, you may structure it as you like with the following conventions for any data that will have direct
use in the UI:

A component's data in the state _must_ be a map that:
- _Must_ include a key `:id` with a *unique* value (within that app-state). This will be error-checked for you at runtime. If
you omit the `:id`, it will not be reachable without entanglement (e.g. you won't be using this library)
- _Should_ include any key/value pairs to model the local state of that component, including transient state (e.g.
`overlay-visible?`)
- _May_ include data for sub-components that are "managed" by this component (and therefore have no `:id`). In other 
words, you may define what "local reasoning" means for your component. There may be tight clusters of components 
(e.g. an input field component within a form) that wish to have some localized coupling and don't need this library's
direct support.


     ; application state namespace
     (defonce app-state
       (atom 
          (qmodel/identify-state
            {
               ; two things that are not component state...app-specific purpose. Need not be maps
               :VERSION 1.0
               :some-other-tracking-thing [] 
               :visualizations { ; not a 'component' itself...just part of the organization of state. needs no :id
                                 :main-report { ; component
                                    :id "main-report"
                                    :start-date (cal/make-calendar "vis-main-start-date") ; sub-components
                                    :end-date (cal/make-calendar "vis-main-end-date")
                                    :table-data { :id "vis-main-table-data" :data [] }
                                 }
                              }
            }))
            
The call to `(qmodel/identify-state your-map)` is *required*. It scans you state, checks it for duplicate IDs, and 
augments it with a lookup-by-id map that makes the isolation of data modelling possible. Thus, you should never 
overwrite the top-level map with a completely new map without re-calling this method on that map.

Notice that I've chosen to invent an ID scheme in this example that mimics the data structure. This is not necessary, and possibly
not even desirable; however, there is nothing to say that my UI has to mimic this data structure. Since "naming things" 
is one of the [two hard problems of computer science](http://martinfowler.com/bliki/TwoHardThings.html), 
we can do nothing but rely on your own good taste here.

Also note that the application state itself is as simple as it _can_ be. Component model "constructor" functions can
be used to simplify the initialization of this state, and an application start-up function could even set defaults
via a simple `swap!` on this state. AJAX calls (e.g. to fill out the table's data vector) are similarly trivial to implement.

We can summarize the most important point as: EACH component in the UI has a unique ID, and you are required to assign it.
If two different date pickers are there, then each will have a unique ID. If you have 10 pop-up panels that each have 2 date
pickers, then there will be 20 date pickers in your application state, each with a unique ID.

From this point forward, most of your code need only know the unique name that you've assigned to that component. Note
the subtle things we just solved:

- Easy to have "more than one" of anything. Simply add another instance to the app state with an ID.
- Creation of state using localized model-specific constructors encouraged, and those constructors need know nothing
  specific about the UI (other than the general capabilities of it, such as the existence of a hideable overlay).
- The library is now able to locate state by ID, and you are free to (re)structure your application state without worry.

## Writing the UI Component

Next we want to write the actual UI code of the component, and we want to do it in a way that preserves local reasoning,
decoupling from global state, and automatic re-rendering when needed (thanks to Quiescent). 

You define components using the `defscomponent` macro:

    (ns my-app.calendar
      (:require [quiescent-model.component :as c]
                [quiescent.core :as q]
                [my-app.model.calendar :as cal]
                [quiescent.dom :as d]))
                
    (c/defscomponent Calendar
                     "A Calendar" ;; A name/description, as in quiescent
                     :on-mount (fn [&rest] (.log js/console "Hello"))  ;; as in quiescent
                     [calendar-data app-state callback-builder] ;; required signature, you pick the names
                    (let [overlay-visible? (:overlay-visible? calendar-data)
                          move-to-next-month (callback-builder cal/next-month)
                          toggle-days (callback-builder cal/toggle-calendar-overlay)
                          set-date-builder (fn [dt] (callback-builder (partial cal/set-date dt)))
                          ]
                      (d/div (d/span { :onClick toggle-days } ()
                         ...
                         (d/button {:onClick move-to-next-month } "Next Month")
                      ) ...))

There are a number of things to take note of:

- The argument list to your body of the component has three arguments. They will be:
  - The state of your component (you need not worry where it is stored)
  - The overall application state atom, which you should mainly use in an abstract sense to embed sub-components. Accessing
    the items of the app state introduces coupling, while just passing it along as abstract data limits such coupling.
  - A higher-order function for making event handlers for your UI

Now, you can write the rendering itself based on your localized understanding of the component model. E.g. you
simply access the first argument as a "calendar" model:

       (let [overlay-visible? (:overlay-visible? calendar-data)

Also note that all of the UI operations need know absolutely nothing about the location of the data, how it gets updated
or even its format (accessor functions for things like overlay-visible? could give you this property for reads as well).
Basically, the `callback-builder` must be passed a function that can take your components current application state, 
and return the desired new state. Technically it can also have side-effects, but the only thing the callback-builder
is going to pass to your function is the component state. If you have additional arguments, simply use `partial` to
pre-apply those before calling the generated event handler function. For example, clicking on the "day" of a 
calendar component should set the date of that component to the day in question. To implement this, create
a secondary event-handler builder that takes a desired date `dt` and uses partial application to turn a call to `set-date`
into a function that takes a calendar and returns one (this is the reason I recommend passing the component state last
in your models, but you can certainly use lambdas if you're using a component that does not follow this convention):

       (let [set-date-to (fn [dt] (callback-builder (partial cal/set-date dt)))]

and when creating the UI element for a specific day, construct new functions for each day:

       ; assume 'days' is the vector: [ {:month 1 :day 1 :year 2015 } {:month 1 :day 2 :year 2015 } ... ]
       (map (fn [d] (d/span { :onClick (set-date-to d) } (str (:day d))) days)

## Using Your Component

You component body (above) _receives_ parameters passed by the internals of this library. It is very important for
you to note that *you do not pass* these arguments when you use it. This is a significant break from
quiescent.

Instead, `defscomponent` def's a function that can create a React component based on your template (above), the 
application state, and your self-assigned IDs. Thus, you create the instances of your component with:

     (Calendar "vis-main-start-date" app-state-atom)

Now you see that the use of the component also allows local reasoning. The only things you need to know are the ID
of that component (which you defined), and since the template body is _passed_ the app-state-atom
most components need not even know the real name/location of the application state atom!

## Dynamically Generated UI Elements

Many applications require that the user be able to "add" things. The TODO application is a prime example: the whole
point of the application is to allow the user to dynamically add data that then generates new UI elements. This 
library suggests that the data for such elements be locally-owned-and-operated by the component that creates and
edits them. This means they do *not* generally have any global unique ID, nor do they need it. It also implies that
you will simply use the mechanisms provided by quiescent itself to model these sub-components.

Thus, the short answer to this problem is: Use quiescent to create these sub-components on your localized model. This
preserves simplicity, rendering efficiency, and local(ized) reasoning. Use the callback-builder to pass your 'hook-ups'
through the "constant argument" parameter of quiescent. Those pre-build functions include closure over functions
that will cause them to "just work"; however, be sure you understand that your subcomponents will follow the 
re-rendering rules of quiescent (if the first arg doesn't change, they won't update).

## License

Copyright © 2015 NAVIS All Rights Reserved
