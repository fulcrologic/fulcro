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

