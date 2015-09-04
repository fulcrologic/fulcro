# REPL Support (assumes you are using figwheel)

Untangled includes support for debugging applications in the form of tools that let you watch, in real time, the
application state. To use this support, you must do a small bit of initial setup.

## Setup 

First, create a cljs namespace that will be available during development (e.g. cljs.user). Within this namespace you
should add the following:

    (ns cljs.user
      (:require [your-ns-with-app-state :as s]
                [untangled.repl :refer [fpp focus-in focus-out vdiff auto-trigger! evolution]]
                )
      )

    (untangled.repl/follow-app-state! s/app-state s/undo-history)

Where `s/app-state` and `s/undo-history` are the atoms holding your application state and undo history.

## Usage

### Showing application state

Since your application state is just a tree, the REPL tools give you a way to analyze that data
structure much like you would a filesystem. Once it knows where your application state is, you can show the state using
`fpp`, which stands for "focused-pretty-print".  It will print in the REPL and JavaScript console.

### Focusing in on sub-state

Of course, rather than having to parse through all of that mess, you probably want to focus on some specific path. This
is where `focus-in` and `focus-out` come in handy. Think of them like command line `cd`:

     (focus-in :todo-list)   ; like: cd todo-list
     (focus-out)             ; like: cd ..
     (focus-in [:todo-list :items 0]) ; like: cd todo-list/items/0
     (focus-out 2)           ; like: cd ../..

Once you are focused, `fpp` will print the state at that location instead of the entire application state.

### Exploring History

When you enable undo history on your Untangle application (even if you do not expose it to the user), then you gain 
access to a git-like analysis of your application's state! So, even if you don't plan on exposing undo to the user, 
you can at least enable it during development to get access to the following extremely useful tools:

#### Terse diff

The `diff` tool shows a summary of changes in minimal format (using the `differ` clojure library). It takes one or two
arguments. With one argument, it shows the diff between the current app state and `n` steps ago. With two, it diffs n and m 
steps ago.

#### Verbose diff

When comparing two points in history with some distance apart, it is useful to just see the whole (focused) data 
structure at each point. `vdiff` allows you to do this, and accepts the same parameters as `diff`.

#### Live updates

If you call (auto-trigger! true), then the JavaScript console of your application will show each app state change (in 
terse diff format) each time it changes.

#### Evolution

The `evolution` function shows you step-by-step terse diffs from one point in history to another. The output 
looks just like the live updates.
