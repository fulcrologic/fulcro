(ns fulcro-tutorial.A-Introduction
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim]
            [fulcro.client.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(defcard-doc
  "# Introduction

  Welcome to Fulcro!

  This tutorial will walk you through the various parts of Fulcro in a way that should then allow you to easily develop
  your own full-stack web applications!

  Note that you can navigate to the table of contents any time using the `devcards` link at the top of the page.

  ## What is Fulcro?

  Fulcro is a full-stack library based upon the concepts of Om Next by David Nolen. It allows you to build data-driven, full-stack,
  single-page software using a well-rounded set of primitives. Think React+Redux+GraphQL, but with a *lot* less boilerplate and incidental
  complexity, and OO noise.

  Fulcro does require that you change how you think about developing your software, but the good news is that
  once you understand the model you'll be surprised at how uniform and simple most tasks become (all the way end-to-end,
  not just in the UI).

  Oh, did we mention you get to use the same advanced functional programming language on the
  client and server? That hot code reload is *actually* useful (as opposed to a novelty that rarely does)? That you can
  debug errors from the field against the *state history* that the user's browser that experienced the error? Did you
  know that Clojure and Clojurescript usually require
  [a lot less code](http://blog.wolfram.com/2012/11/14/code-length-measured-in-14-languages/) to accomplish a given task?

  We hope you'll give it a shot, and a little time to really learn it. We think this combination of idea and programming
  language hit a real sweet spot for software development.

  ## Where Can I see Working Demos???

  This tutorial contains a lot of active running code. Any time you see a card like this:
  ")

(defcard A-live-card
  (fn [state _]
    (dom/div nil
      "I'm Live Code!"
      (dom/br nil)
      (dom/button #js {:onClick #(swap! state update :n inc)} (str "I've been clicked " (:n @state) " times!"))))
  {:n 0})

(defcard-doc
  "
  then you're looking at something that is running live in your browser. Much of the source you see in this document
  is also being pulled directly from the source files to help ensure they are in sync.

  Your primary reference (which includes a lot more running code) is the [Fulcro Developer's Guide](http://book.fulcrologic.com].

  Some other things that show off more advanced features as standalone demos:

  - A Websocket Chat Application: [https://github.com/fulcrologic/websocket-demo](https://github.com/fulcrologic/websocket-demo)
  - The lein template `lein new fulcro my-app`. A minimal full-stack app, but includes a *lot* of production-necessary bits
    like testing (locally and with CI), server, and client.
  - A template application. This one has a bit more code in it, and demonstrates HTML5 routing, Server-Side (isomorphic) rendering,
  a little bit of login logic. [https://github.com/fulcrologic/fulcro-template](https://github.com/fulcrologic/fulcro-template)

  ## Why Clojure and Clojurescript?

  1. *Dramatically simpler language.*
  Clojure has very little syntax. At first this seems a liability until you realize the
  amount of boilerplate it eliminates. What's more, because the language is written
  as a data structure it means that metaprogramming (augmenting the compiler to do
  something new) is nearly as easy as writing regular code. This means things like
  domain-specific languages are easy to create for your specialized problems. A typical
  Clojure program is significantly shorter than what you're used to in Java or Javascript.
  2. *First-class immutable (persistent) data structures.*
  If you're coming from the non-functional world this seems like a very odd thing at
  first, but they are the basis of being able to clearly reason about code. Once you
  adjust to the fact that you can trust your values, you'll find a lot of bugs simply
  never happen!
  3. *Since the code itself is written in a data language it which means you can easily transmit it, store it, transform it, etc.*
  This leads to really interesting features, like richer on-the-wire protocols and our support viewer. No more sending
  home-grown JSON or string-encodings in deal with information!
  4. *Great support for concurrency.*
  This is more of an advantage on the server, but Clojure was designed with concurrency in mind, and we think it has a
  cleaner picture than most languages.
  5. *One language on both the front and back-end that isn't Javascript + Node.*
  Javascript is a very old language with a lot of baggage, confusing constructs, and strange behaviors. We're used to it,
  but it is very easy to make a big mess with it; however, we do see the value in having a single language that runs
  across the stack. The JVM is a very mature technology, and gives you great access to tools like YourKit and more
  high-quality runtime libraries than any other platform.

  It is likely that you can dive in and start playing with Fulcro without knowing too
  much about Clojure, but you should check out a book like
  \"Clojure for the Brave and True\" to at least get through the basics. There is a great site for practicing the
  basics at [http://www.4clojure.com](http://www.4clojure.com).

  ## History

  The ideas for data-driven applications were pioneered in the production space by Netflix and Facebook. The Clojurescript
  community adopted React very early because it was such a great fit for doing rendering in a functional programming
  language. David Nolen pioneered the data-driven Clojurescript movement with a library called Om Next. Fulcro started out
  life as an add-on library for Om Next known as Untangled.

  Fulcro has always meant to be for production use, but Om Next has been more of an experimental library.
  It took 2 years to get to a beta1, and has been sitting idle at that same spot for six months at the
  time of this writing due to other demands on David's time. So in late 2017 Fulcro 2.0
  became a standalone library in 2017 in order to
  expand a number of things and address some prevailing issues of using Om Next as a basis for production code and
  provide users with a better overall experience and more reliable updates.

  Fulcro 2.0 is has a look-alike internal API based upon Om Next.
  and has introduced facilities that enabled features that were not possible with Om Next's internals.

  ## About this Tutorial

  This tutorial is written in Bruce Hauman's excellent Devcards. As such, these documents are live code!

  This file, for example, is in `src/tutorial/fulcro_tutorial/A_Introduction.cljs`. If you followed the README to start
  up this project, then you're reading this file through your browser and Bruce's other great tool: Figwheel. The
  combination of the two bring you documentation that runs and also hot reloads whenever the files are saved.

  If you open this file in an editor, edit it and save, you'll see the browser automatically refresh the view. This allows
  you to participate in the tutorial on your own machine with real local tools.

  The box below, for example, is generated by a devcard:

  ")

(defcard sample-card (dom/div nil "The following number is calculated: " (+ 3 6)))

(defcard-doc
  "
  Open up the `A_Introduction.cljs`, search for `sample-card`, edit the numbers, save, and watch this page refresh. You
  are encouraged to play with the source code and examples in the tutorial to verify your understanding as you read.
  Devcards support state as well, and will track it in an atom for you. Thus, you can generate UI that actually responds
  to user interaction:
  ")
(defcard interactive-card
  (fn [state-atom owner]                                    ;wrapper function that can accept a state atom
    (dom/div nil "A single top-level element."
      (dom/span nil (str "value of x: " (:x @state-atom)))
      (dom/br nil)
      (dom/button #js {:onClick #(swap! state-atom update-in [:x] inc)} "Click me")))
  {:x 2}                                                    ; This is a map of initial state that devcards puts in an atom
  {:inspect-data true})                                     ; options....show me the current value of the data

(defcard-doc
  "
  Notice that if you edit the code in the card above and save that it *does not* lose track of state. Figwheel does hot
  code reloading, but because of pure rendering and immutable state devcards is able to safely hold onto the state of the component.
  If you make dramatic changes (where the state really does need to change) then you will need to reload the page via the browser
  to clear/reset that state.

  IMPORTANT IF YOU GET STUCK:

  First, if there is no obvious error in the browser try reloading the page.

  If you make a typo or language error Figwheel will usually describe it pretty well in the browser.
  However, it is possible to get the whole thing stuck. Typing `(reset-autobuild)` in the REPL will clean the sources and
  rebuild (and you'll see compile errors there). Correct the errors and everything should start
  working again.

  Try NOT to kill the REPL and restart, as that will cause you a lot of waiting as
  you get compile errors, edit, and restart. (If you do kill the REPL,
  you might even consider using git to undo your changes so that it will restart cleanly).

  The nuke option: Occasionally you might see weird behavior (all of these tools are relatively new). At that point it
  might make sense to kill the REPL, use git (e.g. stash) to get to a known good source state, clean things with `lein clean`,
  and then restart.

  ## Notes on documentation

  There are wrappers for plain DOM elements which take as their second parameter a javascript map (not a cljs one) or nil. As such, you
  usually write your base DOM UI like this:

  ```
  (dom/div #js {:onClick (fn [evt] ...) })
  ```

  in some of the examples you'll see this instead:

  ```
  (dom/div (clj->js {:onClick (fn [evt] ...) }))
  ```

  The two are equivalent (though the former is less runtime overhead). The latter will do a recursive transform, which can be handy.

  Later, when we define our own elements we'll end up using cljs data structures (no `#js`).

  [Let's start with a Quick Tour.](#!/fulcro_tutorial.A_Quick_Tour)
  ")
