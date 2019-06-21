3.0.0-alpha-11
--------------
- Fixed clj render to work well with css lib
- Switched react back to cljsjs...no need for that break

3.0.0-alpha-10
--------------
- Added missing augment-response helper to mware
- Fixed a couple of bugs in optimized render
- Fixed bug in new db->tree
- Added missing link query tsts for db->tree
- Fixed bug in tx processing for remote false
- Added more to html events ns
- Updated specs for form-state

3.0.0-alpha-9
-------------
- Improved ident-optimized render, added support for :only-refresh
- Added specs and docs strings
- Fixed some naming where registry key should have been used
- Fixed componentDidMount prev-props (failing to update)
- Fixed tempid rewrites on mutation return from server
- Changed transit stack. Updated dev guide for it.
- Deprecated some names
- Added SSR render-to-string support
- Switched to ghostwheel 0.4 for better spec elision in production builds

3.0.0-alpha-8
-------------
- Improved logging helpers
- Some UISM touch-ups
- Minor fixes around mount error checks and debug logging

3.0.0-alpha-7
-------------
- Improved hot code reload (app root and dyn router)
- Workaround gw bug
- Made will-leave and route-cancelled optional on dr route targets
- Did some minor renames in UI state machines.
- A number of issues fixed in UI state machines that were caused by some
renames.

3.0.0-alpha-6
-------------
- Added official hooks to a number of places, and added a bit to docs
- Finished defining the "default" amount of pluggability for default mutations
- Make global query transform apply closer to the network layer to catch both mutations and queries
- Added confirmation tests to some more elements of merge join and pre-merge

3.0.0-alpha-5
-------------
- Fixed Fulcro Inspect db view (was missing deltas)
- Added missing support for returning/with-params/with-target to mutations,
but change remotes to allow `env` in addition to boolean and AST so that
the threading of those functions are cleaner.
- Added/modified how custom start-up state is given to an app.  
- Fixed rendering bug so that app normalization is optional (useful in small demos)

3.0.0-alpha-4
-------------
- Fixed dependency on EQL

3.0.0-alpha-3
-------------
- Fixed bug in tx processing
- Fixed missing refresh in ident optimized render when using ident joins
- Added missing indexes
- Added various missing functions and namespaces

3.0.0-alpha-2
-------------
- Added Inspect preload so F3 can work with Chrome extension
- Updated readme
- Integrated many namespaces from F2: form-state, icons, events, entities...
- Added numerous tests.
- Added a few more missing utility functions

3.0.0-alpha-1
-------------
- Major APIs all ported, and somewhat tested
- Websockets is in a separate library (reduced server deps)
- Not API compatible with F2 yet. See todomvc sample source for basics


3.0.0-pre-alpha-5
-----------------
- Added shared back. Forcing root render and `update-shared!` updates shared-fn values. Slight operational difference from 2.x.

3.0.0-pre-alpha-4
-----------------
- Added real and mock http remote
- Added server middleware
- Ported (but untested) uism and routing

