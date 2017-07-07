# The Benefits of Untangled

In a nutshell: Untangled eliminates a lot of incidental complexity. It allows you to think
about rendering as a pure function of data, which then allows you to think about the
clean evolution of your data model from one state to the next through *mutations* on that
data model (atomic steps that complete one operation). The UI pretty much takes care
of itself.

In the interest of giving you some talking points:

## Why to Use Untangled

- Use an advanced, fast, FP language on both the client and server.
- Reason about the UI as a pure function (no "bit twiddling" to modify the DOM).
- Reason about the data model as a pure graph of data (mostly separate from the UI).
    - Data ends up in easy-to-access linked table-like structures that make understanding and updating the data easy.
- Clean, unit-testable *mutations* evolve the data model. The UI takes care of itself through two easy to understand
mechanisms (no two-way data binding causing (or failing to cause) storms of refreshes):
    - The UI refreshes anything that triggers a mutation whose data has actually changed.
    - Through listing (abstractly) what data in the model a mutation affects.
- One langauge is used on both client and server. Like node, but without the Javascript.
- A full-stack story that unifies how your model is treated on both the client and server.
- It is [React](https://facebook.github.io/react/)-based: The rendering itself is done by a widely used, supported, and robust library.
- The data and communication model is similar to that of GraphQL and Falcor, but simplified via a concise Datomic-like graph query language.
    - Read about [data driven architectures](https://medium.com/@env/demand-driven-development-relay-falcor-om-next-75818bd54ea1).
    - The Om Next model makes [CQRS](https://www.youtube.com/watch?v=qDNPQo9UmJA) pretty easy to add for both auditing and performance.
- It has a strong FP flair:
    - Rendering is done as a pure function.
    - No in-place mutation (persistent data structures).
    - UI History and time travel are supported features (including a support UI VCR).
- It leverages Google Closure for js optimization, so you get these for free (with little headache):
    - Dynamic module loading (code splitting)
    - Minification
    - Dead code elimination
    - A large library of reusable functions (Google's Closure library)
- UI in React + cljc means that client and server-side rendering of initial loads is easy to get.
- You get to think of your application almost completely as a pure data model.
- A gettext-based internationalization system.
- Meta-programming is quite powerful when used well (think of building a DSL that can then be used to build
elements of your program). Clojure is homoiconic, making this easier.

## When not to Use Untangled

Untangled does try to provide you with a full-stack story. It also requires that you learn
(and *unlearn*) a few things that some people find initially challenging. Here are some reasons
you might *not* want to use Untangled:

- You are writing a game. Untangled shines when it comes to data-driven applications. Games typically need very fast
  framerates and low UI overhead. Untangled is fast enough for data-driven apps, but it really would not make
  sense for animation-heavy gaming.
- You don't want to learn something radically different from what you're used to.
- Your co-workers don't want to learn something radically different.
- Your company cannot be convinced that the long-term benefits of Untangled will pay off in the long run compared to the
costs of re-training/tooling.

