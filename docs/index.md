# Untangled

Untangled is a library for building full-stack webapps using the Clojure and Clojurescript programming languages.
It leverages Om Next and a number of other libraries to provide a strongly cohesive story that has many 
advantages over techniques, libraries, and frameworks you might have used before.

## Is Untangled for me?

Evaluating tools for doing web development can be a monumental task. Untangled has a particular
class of problems it is trying to address well, and may or may not be a good fit. To help
you evaluate it, we provide some basic facts about it:

- One langauge is used on both client and server. Like node, but without the Javascript.
- It is React-based: The rendering itself is done by a widely used, supported, and robust library.
- The data and communication model is similar to that of GraphQL and Falcor, but simplified via a concise Datomic-like graph query language.
   - Having a well-defined data model and network protocol lead to a lot of powerful results:
       - The application can tune itself to ask for just what it needs. This enables different versions of your application (e.g. mobile vs desktop) to modify what they ask for to minimize network overhead.
       - The protocol is EDN, a rich (and extensible) on-wire protocol that is also what the programming language itself is written in and uses.
       - ...
       - CORS is pretty easy to add as a middle layer for both auditability and performance.
- It has a strong FP flair:
    - Rendering is done as a pure function
    - No in-place mutation (persistent data structures)
    - UI History and time travel are supported features (including a support UI VCR)
- It leverages Closure for js optimization, so you get these for free:
    - Dynamic module loading
    - Code splitting
    - Minification
- Writing the UI in React and the program in clj/cljs means that server-side rendering of initial loads is easy to get.
- You get to think of your application almost completely as a pure data model.

Untangled does try to provide you with a full-stack story. It also requires that you learn 
(and *unlearn*) a few things that some people find initially challenging. Here are some reasons
you might *not* want to use Untangled:

- You are writing a game. Untangled shines when it comes to data-driven applications. Games typically need very fast
  framerates and low UI overhead. Untangled is fast enough for data-driven apps, but it really would not make
  sense for animation-heavy gaming.
- You don't want to learn something radically different from what you're used to. 
   - The model doesn't need or use events or signals (except to consume UI events from user interaction).
   - You rarely fiddle with the low-level DOM
   - You rarely (if ever) side-effect in the view.
   - You must learn to think about the UI as a pure function of a data model.

TODO: Link to shared spreadsheet on google docs with evaluation criteria

## What Does it look Like?

A complete overview is available in the [Getting Started](http://github.com/awkay/untangled/GettingStarted.adoc)
guide.
