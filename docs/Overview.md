# Untangled

## Quick Tour (Demo)

Demo of TodoMVC with Datomic server-side integration, internationalized,
VCR viewer, tour of code base and development experience.

## The Client

### The Database (2 slides)

- Om standard format (graph database)
    - Plain cljs data (maps and vectors)
    - Tables are maps keyed by user-defined "object" ID
    - Idents used to create links
    - Leaves of the tree can be anything (functions should be avoided)
    - See online Om Tutorial for more info/exercises

### The UI (4 slides)

- Om standard UI components
    - Generates plain React components
    - Co-located Queries for data
    - Queries compose to root
    - Components can have idents, which defines table/ID of data

### Mutations (3 slides)

- Om standard mutations
    - All changes to database happen through top-level, abstract, transactions
    - Predefined mutate multimethod: simply use `defmethod`
    - Optimistic updates and remote interactions under a single abstraction
    - Extension for `post-mutate` hook (to trigger common behaviors). Multimethod.

### Internationalization (3 slides)

- GNU gettext-style internationalization support
    - `tr` : English string is key
    - `trf` : Formatted (plural support, date, time, numerics)
        - Formatting based on Yahoo's formatjs
- POT file generated from source code (cljs to js, then standard xgettext)
- Translations edited with standard tools, like PoEdit
- Module support for dynamically loading translations at runtime

## The Server (2 slides)

- Based on Stuart Sierra's component library
- Includes components for: logging, config, static resource handling,
  and a web server (currently httpkit)
- Can easily extend/inject your own components

### Configuration (1 slide)

- Configuration via EDN
    - Defaults file in project
    - Custom-named external file overrides
    - Command-line augmentation (e.g. name alt file)

### Injectable Server Components (1 slide)

- Define a component
- Add it to server 
- Can automatically inject it in server-side read/mutations
- Can augment Ring handler

### Datomic Integration (2 slides)

- Separate add-on library to create/support Datomic databases
    - Migration support
    - Seeding support (for testing/development)
    - Optional extra schema validation 

## The Full Stack

### Remoting (6 slides)

- All network plumbing pre-built
    - One-at-a-time processing semantics
    - Full optimistic update (UI instantly responsive)
    - Pluggable network components
    - Support for unhappy path (network down, server errors)
- Query attributes namespaced to `ui` are automatically elided
- Complete tempid handling
    - Rewritten in state *and* network request queue
- Smart merging:
    - Deep merge is the default
    - Automatic handling of multi-source merges: A/B ask for different
      attributes of the same object...when to stomp vs merge?
      
### Initial Load (1 slide)

- All loads are explicit
    - Initial loads triggered via startup callback
    - Queries based on UI or just custom queries
    - Post-processing Support
    - UI query filtering (e.g. `:without`)

### Lazy Loading (4 slides)

- Lazy loads can be triggered at any time
   - Can be field-based if the component has an Ident. Generates the query for you.
       - Supports callback post-processing, `:without`, etc.
       - Places marker in state at proper location for "spinner" rendering (UI helper)
           - Seeing marker requires that you query for `:ui/fetch-state`

### Testing (4 slides)

- Helpers and rendering stacked on clj/cljs test
   - Outline-based rendering
   - Human readable data diffs and output
   - Advanced mocking
   - Timeline simulation for testing async behaviors

#### Protocol Testing (2 slides)

- Gives end-to-end testing without the integration pain
   - Proof of correctness through shared data

### Support VCR Viewer (1 slide)

- Allows an end user to submit a problem report that includes a recording of
their session
    - Play forward/backward
    - Timestamps of interactions
    - Server error recording (with server timestamps)

## Dev Tools (2 slides)

- Click-to-edit components
- Database browser/query tools

