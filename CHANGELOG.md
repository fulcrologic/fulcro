NOTE: Version numbers use the following scheme:

x.y.z
- (inc x) = Major change that could break you program. Every attempt is made to keep these breaks
small and tractable.
- (inc y) = Intrusive change that should not break anything, but should be heavily tested.
- (inc z) = additions, bug fixes, etc.

2.5.1
-----
- Fixed a bug in the spec for localized dom on server

2.5.0
-----------
- Added support for :focus on loads

2.5.0-beta1
-----------
- Fixed issue with internal merge on mutation joins getting :not-found in idents
- Fixed a bug in forms that was trimming values incorrectly with new DOM
- Various tweaks and fixes to book

2.5.0-alpha4
------------
- Namespace fix in book source
- Added svg use tag

2.5.0-alpha3
------------
- Fixed a bug on wrapped select with ref

2.5.0-alpha2
------------
- Promoted fulcro.client.alpha.dom to fulcro.client.dom
- Promoted fulcro.client.alpha.localized-dom to fulcro.client.localized-dom

2.5.0-alpha1
------------
- Dramatic cleanup reduces dependencies WARNING: If you use websockets or server namespaces, you must now explicitly add the dependencies to your project. Dynamic loading errors will report what is missing.
- Released new i18n (alpha promotion). BREAKING CHANGE.
- Behavioral change: The deprecated fulcro.client.logging is now just a facade to fulcro.logging
- Moved tutorial to a separate repo

2.4.4
-----
- Fixed bug in alpha dom. Lazy seqs were sometimes not properly expanded.

2.4.3
-----
- Bug in specter WRT cljs, so changed fulcro-css use clojure.walk for localizing things
    - Also eliminated that dependency
- Updated alpha dom to support expressions in props
- Added `sc` macro, a non-def version of `defsc`

2.4.2
-----
- Fixed bug in alpha dom select input

2.4.1
-----
- Fixed dynamic query ID generation under adv optimization
- Fixed bug in mutation joins (server return values)
- Removed support for bindable parameters in queries. Obscure feature no one was using that was broken.
- Fixed bug in book demo

2.4.0
-----
- IMPORTANT CHANGE: Integrated Fulcro CSS. You should remove fulcrologic/fulcro-css from your dependencies,
and exclude it from any libraries that bring it in.
- Fixed bug in `:initial-state` when mixing template and lamda forms in different components.
- Added alpha versions of new, tighter, DOM functions that do not require props, or #js
- A few minor bug fixes in i18n alpha
- PORTING TO fulcro.client.alpha.dom
  - You cannot use expressions (e.g. list forms) for props. Only symbols, maps, or js objects
  - Proper runtime optimization cannot be done for isomorphic rendering with a single namespace (this was a bug inherited from Om Next). For CLJC, the correct require is now:
  ```
  #?(:clj [fulcro.client.alpha.dom-server :as dom]
     :cljs [fulcro.client.alpha.dom :as dom])
  ```
- Added `fulcro.client.alpha.localized-dom`. Syntax is just like the new `alpah.dom`, but
class keywords are localized to the *component* via fulcro-css rules.

2.3.1
-----
- Added fulcro.alpha.i18n. A rewrite of i18n support.
- Fixed problem related to a change in CLJS 1.10+
- Fixed `:ref` support on wrapped inputs
- Added a `make-root` helper to the fulcro.client.cards ns for helping with devcard creation

2.3.0
-----
- Fixed bug in dirty-fields for form state, where relations were not properly reported.
- The remote plumbing layer had a few intrusive internals change that should have no
  external visible effects.
- NOTE: Fulcro Inspect must be upgraded. It required changes to support the new remoting.
- New remoting protocol and implementation: FuclroRemoteI and FulcroHTTPRemote
    - Supports aborting any remote request (load/mutation) from queue or active network
    - Supports giving progress updates via a general transaction
    - Support request and response middleware
- Updated developer's guide to include new remoting
- Requires an update to Fulcro Inspect
- Client still defaults to *old* networking code. Using abort, progress,
and middleware is opt-in for now.
- Made a set-route mutation in routing (augments the set-route mutation helper)
- Made routing param helper public
- Added support for custom database query interpreter on the *client* database,
  instead of just a read helper. See the `:query-interpreter` parameter on client creation.
  This allows for easy integration of things like pathom to more easily customize the entire
  client database engine.
- Added support for putting params on a join key, instead of in a list outside of the join.
- Removed some dead code in primitives
- Removed dom dependencies in routing and data fetch, to enable easier use with React Native
- BREAKING CHANGE: new Websockets API support refined and improved a bit, but the argument lists for construction changed slightly.

2.2.2
-----
- Fix to SSR on routing changes
- Improved get-ident to work consistently on server and client

2.2.1
-----
- Improved UI routing documentation and API.

2.2.0
-----
- Possible breaking change: Make logging no longer require timbre. fulcro.client.logging is deprecated. If you
were accidentally relying on Fulcro's import of timbre, you may have to manually add it to your dependencies now.
- Added fulcro.logging: allows you to plug in a logger to log messages from internal fulcro functions
- Added new version of websockets. Deprecated old.

2.1.7
-----
- Fixed network history tracking. Was clogging up due to bug in remote activity tracking.
- Corrected counting on active remotes (more than one remote interaction can happen per tx time)

2.1.6
-----
- Made parent-with-context work with SSR

2.1.5
-----
- Added `prim/with-parent-context` for cases where you're using child-as-a-function
react pattern and the Fulcro internal bindings are lost.
- Made load work with dynamic queries

2.1.4
-----
- Alpha release of new form state support
- Fixed a bug in load markers: placed incorrectly when using marker table
- Expanded defsc to support new form fields

2.1.3
-----
- Added common non-printable key code detectors in fulcro.events
- Added all common HTML entities (unicode strings) like `&nbsp;` into fulcro.ui.html-entities
- Some typo fixes
- Some tutorial typos
- Moved bootstrap docs to book

2.1.2
-----
- Thomas Heller helped find a better fix for the compiler hack in defui. Statics and
advanced compilataion are now much cleaner.

2.1.1
-----
- Fixed remote detection in `ptransact!` to use application state so that
it more reliably detects which mutations are remote in a tx.
- Fixed bug related to union app state initialization 

2.1.0
-----
- See betas below
- Removed reference to missing externs file

2.1.0-beta3
-----------
- Added param coercion to routing
- Updated `current-route` to work on state or the routing table itself
- Updated docs to reflect the above two

2.1.0-beta2
-----------
- Fixed initial state generation for SSR

2.1.0-beta1
-----------
- Refactored rendering
   - Added support for alternate rendering modes
 - Made force update override shouldComponentUpdate
   - Eliminates the need for `react-key` on root element
- Fixed bug with dynamic locale loading UI refresh (timing issue)
- Considerable revamping of documentation
- Significant change to the internals of i18n. Should work better when
  multiple apps are on the screen.

2.0.1
-----
- Fixed transact! follow on reads. The declared refresh fix had accidentally
dropped processing the explicit follow-on reads.

2.0.0
-----
- Minor bug fixes in `ptransact!` (ref not required) and `merge-component!` (UI refresh)

2.0.0-RC4
---------
- Added support for ::prim/ref in fallbacks
- Refactored a bit of internal fallback logic
- Tweaks to docs and demos
- DOM inputs (input, textarea, select, option) have a raw mode with
  JVM option `-DrawInputs`. This removes the legacy Om next-style wrappers
  around the inputs. Opt-in for now. Will become the default in future versions.
- Removed the cljs compiler hack for adv optimization. Fixed it in a way that is localized to Fulcro. This
fixes the madness with needing a production profile to build adv builds!
- Added support for `:initialize` option to load (ALPHA. In flux)

2.0.0-RC3
---------
- Documentation updates.
- Removed old sanity check for load parameters.

2.0.0-RC2
---------
- History bug fixes: network activity completion was corrupting history
- I18N bug fix: missing locale causing missing messages
- Added support for fallbacks in `ptransact!`

2.0.0-RC1
---------
- Fixed bug in `ptransact!` where non-remote calls would not be called if
  the earlier ones were not remote. New behavior is to cluster
  non-remote mutations together in front of the "next" remote one, then
  defer the rest and repeat.
- Added ref to env of mutations that run through `ptransact!`
- `ptransact!` API now identical to `transact!` (added ref support)
- Better error checking in defsc for improper use of initial-state template mode
- Added support for routers to pass computed through
- A few fixes to bootstrap support

2.0.0-beta7
-----------
- Added support for targeting return values from mutations

2.0.0-beta6
-----------
- website updates
- load-field and load-field-action now accept a parameters map. Maintained
  named parameter support so it does not break existing code.
- Fixed a bug in SSR lifecycle handling
- Fixed a bug in refresh related to the new multiple-target support in load

2.0.0-beta5
-----------
- Massive documentation update
- Converted most demos and docs to use `defsc`
- Added support to `defsc` for `*` to work right in query template form
- Removed devguide Glossary
- Renamed a bunch of server dev guide sections

2.0.0-beta4
-----------
- Expanded defsc
  - Now supports lifecylcle methods first-class
- Fixed a bug in defsc query validation
- Documentation improvements

2.0.0-beta3
-----------
- Bug fixes and doc improvements

2.0.0-beta2
-----------
- Bug fixes and doc improvements

2.0.0-beta1
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
   - Options are optional, so you can generate a plain react component
   - Parameters to the lambda forms get `this` and `props` from main args, so you don't list them.
     (e.g. `:ident (fn [] ...use this and props from defsc args...)`
   - BREAKING CHANGE:
     - The 4th argument is now children IF you use `:css`.
     - The 4th argument is now an error if there are no CSS rules.
   - Defsc now considers all arguments *after* props to be optional, so you don't need to list underscores
- Removed deprecated fulcro.client.cards/fulcro-app. Please port to `defcard-fulcro` instead (trivial port).
- Moved defsc from fulcro.client.core to fulcro.client.primitives namespace
- Renamed fulcro.client.core to fulcro.client
- Code motion (old names refer to new ones, so no action needed on 1.x apps)
  - iinitial-app-state (now has-initial-app-state?), iident (now has-ident?),
    integrate-ident, integrate-ident!, merge-component, merge-state!
    (now merge-component!), component-merge-query,
    merge-alternate-unions, merge-alternate-union-elements!, and
    merge-alternate-union-elements moved to primitives.
- Removed log-app-state utility. Will reduce production deploy size. (thanks @thheller)
  - Added code for log-app-state to Guide's F_DevEnv documentation, along with a note about fulcro-inspect

1.2.2
-----
- Back-ported improvements to defsc to 1.x dev line. Supports lambdas
for options as well as the original templates. See docstring.

1.2.1
-----
- Expanded tool registry support
- Expanded websocket support a bit
- Fixed elements/ui-iframe in Firefox

1.2.0
-----
- Added support for tools to be notified when a Fulcro app starts so they
can hook in.
- Added a cascading dropdown demo
- Minor fixes
- Added `:set` support to `integrate-ident`

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

