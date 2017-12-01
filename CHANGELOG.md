2.0.0
-----
- Removed dependency on Om Next. See README-fulcro-2.0.adoc for upgrade instructions.
- Imported and refined the useful Om Next abstractions.
- Dropped indexer support for data path, and class path.
- Made post-processing path-meta obsolete (internal change)
- Fixed factory to use `apply` to prevent React warnings when using children.
- Revamped all of the documentation. Made many improvements to Dev Guide
- Added code to support React 16 (optional, defaults to 15)
- Rewrote how dynamic queries work. They are now 100% part of state and history.
- Changed DynamicRouter to use new dynamic query support
- Refined how UI refreshes work. Should be a bit faster that Om Next in general.
- Improved load marker implementation. You may now give markers a name and have them normalize instead of overwriting your data
- Improved `:target` support in all variants of load. You can now target multiple locations, including append/prepend to existing
to-many collections (see the doc string).
- Improved start-up failure messages for server related to configuration files.
- BREAKING CHANGE: load-action and load-field-action require the env (they used to allow env or just the state atom).
- Added a new history system with the following improvements
    - The history sequence is now a unidirectional list of nodes that are annotated with the action
    that affected the change, db-before, db-after, and also with live tracking of network activity relating
    to state changes. This improves debugging abilities dramatically, and also enables better internal
    automated error recovery.
    - Added support for compressible history edges. This allows adjacent history entries to auto-compress into the most recent.
    - Added a better API for accessing and navigating history
- Added support for client mutations to declare a refresh set so that UI follow-on reads are no longer necessary.
- Added more examples in demos
- Added pessimistic transaction support with ptransact!
- Added compressible transaction support with compressible-transact!
- Both of the above use support functions to rewrite the transaction, so you can still use transact! if you like.
- Mutations can now be interned. This enables docstrings and navigation to work for things like nREPL, and also allows you
to use devcards mkdn-pprint-source with mutations in your devcards. See the docstring of defmutation for more details.
- Demos compressed into fewer source files.
- Websockets demo moved to external project
- Expanded and cleaned up many portions of the developer's guide
- Expanded defsc.
   - It can now take a method body for `initial-state`, `ident`, and `query` in
     addition to a simpler template. Using the method forms loosens verification logic.
   - Parameters to the lambda forms get `this` and `props` from main args, so you don't list them.
     (e.g. `:ident (fn [] ...use this and props from defsc args...)`
   - BREAKING CHANGE: IF you use `:css`, then the children argument shifts right one, and
     the 4th argument is now the css classnames.
   - Defsc now considers all arguments *after* props to be optional, so you don't need to list underscores
- Removed deprecated fulcro.client.cards/fulcro-app. Please port to `defcard-fulcro` instead (trivial port).

Renames:
- Moved defsc from fulcro.client.core to fulcro.client.primitives namespace
- Moved and renamed iinitial-state and iident from core to primitives
- Renamed fulcro.client.core to fulcro.client

1.2.1
-----
- Expanded tool registry support
- Expanded websocket support a bit
- Fixed elements/ui-iframe in Firefox

1.2.0
-----
- Added support for tools to be notified when a Fulcro app starts so they
can hook in.

1.1.1
-----
- Fixed assert/async bug

1.1.0
-----
- Added support for shadow DOM (for browsers that support it)
- Fixed typo in forms on maxLength
- Added support for FormatJS custom formats (trf). No SSR support for now.
- Upgraded IntlMessageFormat to version 2.2.0
- Added support for params and refresh list in on-form-change
- Added explicit elide-asserts false to project because of core-async bug

1.0.0
-----
- Stable release

1.0.0-beta11
------------
- Fixed regression in i18n change-locale.
- Fixed routers to allow initial state parameters to pass through to the default screen.
- Fixed a bug in dynamic routers that caused updated route queries to not be properly saved in app state.

1.0.0-beta10
------------
- Fixed a bug in logging where the clj and cljs API didn't match
- Added `defsc` macro to core namespace and documented in devguide section M05
- Added a cheat sheet to docs
- Added mutation helper comments to routing section of devguide
- Enhanced the `env` available in post mutations and fallbacks
- Added some content to the reference guide
- Added auto-retries on dynamic route loading

1.0.0-beta9
-----------
- Fixed bugs in gettext defaults for i18n extract/deploy.
- Finished adding i18n dynamic module loading support to generated locales.
- Added demo of dynamic locale loading.
- Fixed a force-refresh bug in i18n on locale change.
- Updated i18n dev guide documentation.
- Added a demo about using defrouter to switch between lists and editors for the items.
- Added colocated CSS demo.
- Increased the extra attrs that `form-field` allows on most built-in types (e.g. text, html5), to include `:ref` among others.
- Fixed but in dynamic routing that was causing query to not change correctly
- Fixed mark/sweep to not leave not-found idents lying around
- Added demo for new fulcro-sql graph query against SQL
- Bugfix: fixed joins on :ui attrs to work right with mark/sweep on loads
- Changed initialization order around networking to prevent async races
- Moved alternate union initialization to before initial render

1.0.0-beta8
-----------
- Added dynamic routing with code splitting support (not ready for prime time: waiting for compiler fixes still)
- Added demos for defrouter as a detail viewer and dynamic routing
- Fixed a bug with route params that caused numbers to not be usable as parameters
- Continued expanding reference guide
- Added more info on writing custom form submission to Dev Guide Form Server Interaction section.
- Changed CI to use Circle CI
- Cleaned up forms support a bit, and improved docs strings some


1.0.0-beta7
-----------
- REQUIRES: Clojurescript 1.9.854+.
- BREAKING CHANGE: Removed passing `app` to networking. This caused a dependency loop that was unsolvable internally and
  has always been broken. If you have custom networking you'll have to remove that param to start.
- NOTE: You must upgrade fulcro-spec (if you use it) to beta7 as well with this release.
- Added initialize-form mutation to forms with docs in devguide.
- Fixed deprecated ring content-type handling in server.
- Made devcards dynamically load so it isn't a hard dependency.
- Fixed a bug in new initial app state handling.
- Added DynamicRouter to routing. Should work as soon as newest cljs compiler module loader is debugged and fixed.
- Fixed initialize form to work for SSR.
- Quite a number of devguide improvements.
- Got started on a better Reference Guide.

1.0.0-beta6
-----------
- Removed the wrap-defaults module and ring-defaults dependency. It is trivial to write, and pulls in deps that can cause downstream problems.
- Changed :initial-state option: The explicit parameter will override InitialAppState. The old behavior always takes InitialAppState, which turns out to be backwards (and no one should be supplying both unless they mean to override).
- Dramatically improved devcards support: state persistent, eliminated console errors. Must port to new
functions/macros to use. Legacy `fulcro-app` works better than it did, but it is not recommended and will
cause cards that render the same app to collide.
- Worked a lot on the server parts of the devguide. Also fixed some doc strings that still referred to load-data
- Set load markers to be placed when load transacts, instead of waiting for the queue processing. This should help with loading UI flicker.
- Backported to Clojure 1.8. Existing apps should still be able to still use 1.9, but this prevents it from being a requirement.

1.0.0-beta5
-----------
- Made sure that server-side rendering of i18n worked properly, including trf
- Set up client to honor incoming locale from server (if it sets :ui/locale in initial state)
- Removed experimental defui augments. Copy from old source if you use it.

1.0.0-beta4.1
-------------
- Changed the SSR client-side retrieval function to return nil instead of a state marker.

1.0.0-beta4
-----
- Made routing.cljc more friendly to SSR
- Added fulcro.core/merge-component to help merging a new instance of a
component to app state in mutations.
- Added fulcro.core/merge-alternate-union-elements (no exclamation) that can work on state maps.
- Improved CSS on devguide a little
- Made util more SSR-friendly
- Added SSR-related initial state helpers to fulcro.server-render
- Added clj encode/decode to transit string to util
- Added tests for remaining functions in util
- Added section M50-Server-Side-Rendering to the Dev Guide

1.0.0-beta3
-----
- Fixed support viewer. Porting for defmutation had broken it.

1.0.0-beta2
-----
- RENAMED PROJECT: Fulcro. All relevant namespaces and interface names updated to use the new naming. Use bin/rename-untangled.sh to fix your project.
- Added more form field types (html5-input supports all text-like html 5 input types)
- Improved options can be passed when rendering form fields (still needs more)
- Form commit now accepts a fallback
- Improved docstrings on form commit
- Added defvalidator for making form field validators
- Added untangled.client.data-fetch/fallback as the new name for tx/fallback. tx/fallback is still OK, but the new one uses a defmutation, so it pops up in IDEs.
- Added support for user-supplied client read handler

1.0.0-beta1
-----
- Fixed uses of clojure.spec to clojure.spec.alpha
- Integrated devguide
- Integrated cookbook (as demos)
- Removed local storage and async service stuff. It was undocumented, and unneeded.
- Removed `load-data` from data-fetch. Port to using `load` instead.
- Moved internal i18n vars to untangled.i18n namespace
- Removed protocol testing support. Port it from the old library into your project if you need it.
- Removed openid parsing (doesn't belong in this lib)
- Renamed untangled.client.impl.util to untangled.client.util
- Moved force-render and unique-key to untangled.client.util namespace.
- Moved strip-parameters to untangled.client.util (cljc)
- BREAKING CHANGE: Renamed built-in mutations using the defmutation macro.
   - untangled/load -> untangled.client.data-fetch/load
   - tx/fallback -> untangled.client.data-fetch/fallback
   - ui/change-locale -> untangled.client.mutations/change-locale
   - ui/set-props -> untangled.client.mutations/set-props
   - ui/toggle -> untangled.client.mutations/toggle
   Remember that namespace aliasing works within syntax quotes (e.g. `m/change-locale)
- Moved augment-capable defui to augmentation namespace. STILL EXPERIMENTAL.
- Merged server support into this project
- Moved easy untangled server to untangled.easy-server namespace. 
- Moved all other server code to untangled.server namespace.
- Added bootstrap3 namespace with helpers that can render passive and active bootstrap 3 elements.
- If `net/UntangledNetwork start` returns something that implements `UntangledNetwork`, this value will be used as the actual remote by the app.
- Made forms support work (initial render) for SSR
- MOVED pathopt option. It is now just one of the regular reconciler options you can pass. Currently does not default to true due to bugs in Om Next.

0.8.2
-----
- Changed network result handling so that it does not change :ui/react-key (flicker)
- Added support for splitting mutation txes when there are duplicate calls, so that they go over separate network requests to work around Om returning a map.

0.8.1
-----
- Fixed load markers for ident-based loading: They will appear iff the entity is already present (refresh)
- Cleaned up logic around data markers related to markers and data targeting
- Added sequential processing as a configurable option on networking
- Added devcards for integration testing loading cases
- Removed explicit require of devcards.core in devcard untangled-app macro so that devcards is not a hard dependency.
- Updated load to auto-add target or kw of load to the refresh list.

0.8.0
-----
- Added defmutation 
- Fixed up namespaces that defined macros to allow for implicit macro usage by adding self-references
- Added support for multiple remotes (networking option now accepts a map)
   - NOTE: `clear-pending-remote-requests!` now requires a remote parameter.
- Added support for progressive load updates (nice for file
upload support)

0.7.0
-----
- Removed cache 
- Added UI routing helpers

0.6.1
-----
- Added support for nil as subquery class in load
- Fixed preprocess-merge to eliminate litter in app state
- Added support for server-side rendering.
- Removed forced root re-render on post mutations. POTENTIALLY BREAKING CHANGE!
   - The intended use is to include :refresh with your loads that indicate what to re-render
- Fixed bug in InitialAppState that was missing the merge of nested unions on startup

0.6.0
-----
- Changed InitialAppState to overwrite any supplied initial app state atom.
  This allows you to inspect data (the app state) when embedding an Untangled application in a devcard.
- Added new `load` and `load-action` functions with cleaner interface. Deprecated `load-data` and `load-data-action`.
    - Now have the ability to target a top-level query to a spot in app state. Reduces need for post mutations
    - Reduced arguments for better clarity
    - Added ability to pass untangled app, so that use in started-callback is easier.
- Fixed bug in failed loading markers
- Fixed bug with removal/addition of markers when markers are off
- Added jump to and playback speed features to the support viewer.
- Added support for post-mutation parameters in load API.
- Added support for custom handling of merge of return values from server mutations (see `:mutation-merge`
  in `new-untangled-client`).
- Added support for custom transit handlers on the client side. Server side is coming in a release soon.
- Added support for turning on/off Om path optimization
- Fix for latest cljs support (PR 47)
- DEPRECATED: load-data will soon no longer supports the :ident parameter. Use load instead.

0.5.7
-----
- The `:marker` keyword actually works now!
- Fix: data fetch with parameters places the load marker in the correct location in app state
- Fix: error callback doesn't attempt to modify data state in app state db when the data state's marker is false

0.5.6
-----
- Fixed bug with global-error-callback not being called with a server error returns no body
- Fixed bug that prevents error processing if the server is completely down
- Added reset-history! function to reset UntangledApplication Om cache history.
- Added to UntangledApplication protocol:
  * (reset-app! [this root-component callback] "Replace the entire app state with the initial app state defined on the root component (includes auto-merging of unions). callback can be nil, a function, or :original (to call original started-callback).")
  * (clear-pending-remote-requests! [this] "Remove all pending network requests. Useful on failures to eliminate cascading failures.")

0.5.5
-----
- Fixed bug where keywords in a union query were not elided when specified in the `:without` set of data fetches
- Fixed bug with query combining that was causing parallel reads to collide
- Corrected initialization order so that alternates on unions are done before startup callback
- Fixed a rendering refresh bug on post mutations
- Fixed compiler warnings about clojure walk

0.5.4
-----
- Added marker option to loads, so that load markers are optional
- OpenID client will now extract tokens from cookies as well as the header.

0.5.3
-----
- Added utility function integrate-ident!
- Refined merge-state!
- Renamed Constructor to InitialAppState
- Automated initialization of to-one unions (to-many was already doable by Om)

0.5.2
-----
- Fixed bug with initial state and new constructors

0.5.1
-----
- Added untangled/Constructor for adding initial state to UI components
- Added merge-state! for easily merging component-centric data (e.g. from server push)

0.5.0
------
- Significant optimizations to post-query processing.
- BREAKING CHANGE: to load-data. You should now include :refresh to trigger re-rendering of components. This removes the
  internal need for a forced root re-render. Proper refresh after load-data now requires this parameter.
- Removed deprecated load-collection and load-singleton. Use load-data instead (name change only)

0.4.9
-----
- Removed old logging code (use untangled.client.logging instead)
- Added support for parallel lazy loading
- Added `(start [this app])` to the `UntangledNetwork` protocol.
- Log-app-state now requires the atom containing a mounted untangled client, define it in the user namespace like so:
```
(defonce app (atom (fc/new-untangled-client ... )))
(swap! app fc/mount RootComponent "app-div")
(def log-app-state (partial util/log-app-state app))
```
- `global-error-callback` now expectes an arity 2 function. First param is the status and the second is the response.
- Fixed bug that closed over tempids in network callbacks
- Fixed bug in path-optimized union query parsing

0.4.8
-----
- Fixed tempid rewrite regression

0.4.7
-----
- Upgraded to Om-alpha32
- untangled.openid-client/setup, parses any openid claims from the webtoken in the url's hash fragments
- Renamed load-collection/singleton to load-data. Old names are deprecated, but not yet removed.
- Renamed :app/loading-data to :ui/loading-data
- Added remote trigger method for doing loading from mutations.
- Renamed everything in the internals that was prefixed app/ to untangled/
- Added global server error handler.
- Added fallback support for load-data, load-field, etc.
- Refactored networking send for better clarity
- Fixed bug on mark/sweep of missing query results. It was being applied to mutations instead of reads. Added tests for this that need a bit more work.

0.4.6
-----
- Fixed local read bug, and turned on path optimization
- Fixed fallback handling of failed remote transactions, and added lots of tests

0.4.5
-----
- Renamed react-key to ui/react-key
- Renamed app/locale to ui/locale
- Renamed mutation app/change-locale to ui/change-locale
- Implemented a number of missing things in i18n
- Removed a number of i18n helpers that were redundant to trf
- Added :request-transform networking hook with spec
- Modified load callback to a mutation symbol
- Changed :params of loaders to allow you to specify which prop on the query gets the stated parameters
- Added history method to application protocol, to make implementing history viewer in an app trivial

