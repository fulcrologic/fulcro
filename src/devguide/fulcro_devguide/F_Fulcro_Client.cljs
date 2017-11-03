(ns fulcro-devguide.F-Fulcro-Client
(:require [fulcro.client.primitives :as prim :refer-macros [defui]]
  [fulcro.client.dom :as dom]
  [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

; TODO: In these exercises, probably better to have them make an HTML file, a namespace with make/mount, etc.
; See DIV in index.html (have it render over top of tutorial)
; NOTE: in exercises you'll have them MAKE (by hand) initial app state
; TODO: Note diff of atom vs map on I.App State (auto-norm). Exercise?

(defcard-doc
  "
  # Building a Fulcro client

  We're now prepared to write a standalone Fulcro Client! Once you've understood
  how to build the UI and do a few mutations it actually takes very little code:

  The basic steps are:

  - Create the UI with queries, etc.
  - Give a DOM element in your HTML file an ID, and load your target compiled js
  - Create a Fulcro client
  - Mount the client on that DOM element

  We've covered the first step already. The second step is trivial:

  ```html
  <body>
     <div id=\"app\"></div>
     <script src=\"js/my-app.js\"></script>
  </body>
  ```

  The final two steps are typically done such that you can track the overall application in an atom:

  ```clojure
  (ns app.core
    (:require
      app.mutations ; remember to load your add-on mutations
      [fulcro.client.core :as fc]
      [app.ui :as ui]
      yahoo.intl-messageformat-with-locales ; if using i18n
      [fulcro.client.primitives :as prim]))

  (defonce app (atom (fc/new-fulcro-client :initial-state { :some-data 42 })))
  (reset! app (core/mount @app ui/Root \"app\"))
  ```

  This tiny bit of code does a *lot*:

  - It creates an entire ecosystem:
      - A client-side ecosystem with i18n, state, query and mutation support.
      - Plumbing to make it possible to do networking to your server.
      - Complete handling of tempids, merging, attribute conflict resolution, and more!
      - Application locale handling.
      - Support VCR viewer recording with internal mutations that can submit support requests.
      - Support for refreshing the entire UI on hot code reload (with your help).
  - It mounts the application UI on the given DOM element (you can pass a real node or string ID).

  Some additional things that are available (which we'll cover soon):

  - The ability to load data on start using any queries you've placed on the UI or written elsewhere.
  - The ability to do deferred lazy-load on component fields (e.g. comments on an item).

  Wow! That's a lot for two lines of code.

  ## Running Fulcro in a Dev Card

  ```clojure
  (ns boo (:require [fulcro.client.cards :refer [defcard-fulcro]]))

  (defcard-fulcro sample
     \"Optional markdown\"
     ui/Root
     { :some-data 42} ; initial state from the card (If empty, uses InitialAppState which is covered later).
     { :inspect-data true ; devcard options
       :fulcro {:started-callback (fn [app] ...) }) ; fulcro client options
  ```

  ## Initial Application State

  The `:initial-state` option of `new-fulcro-client` can accept a map (which will be assumed to be a TREE of non-normalized data),
  or an `atom` (which will be assumed to be a pre-normalized database).

  If you supply a map, it will be auto-normalized using your UI's query and ident, but if you supply an atom it will be used AS the
  normalized application database.

  We do *not* recommend initializing your application in *either* of these ways except in extremely simple circumstances,
  instead Fulcro allows you to co-locate your initial app state locally on the components so that
  you just don't have to think much about it.

  You should definitely read the next section about [the InitialAppState mechanism](#!/fulcro_devguide.F_Fulcro_Initial_App_State). It
  will make your life easier.

  ")

