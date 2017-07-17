(ns fulcro-devguide.F-Fulcro-Client
(:require [om.next :as om :refer-macros [defui]]
  [om.dom :as dom]
  [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

; TODO: In these exercises, probably better to have them make an HTML file, a namespace with make/mount, etc.
; See DIV in index.html (have it render over top of tutorial)
; NOTE: in exercises you'll have them MAKE (by hand) initial app state
; TODO: Note diff of atom vs map on I.App State (auto-norm). Exercise?

(defcard-doc
  "
  # Building an Fulcro client

  We're now prepared to write a standalone Fulcro Client! Once you've understood
  how to build the UI and do a few mutations it actually takes very little code:

  The basic steps are:

  - Create the UI with queries, etc.
  - Give a DOM element in your HTML file an ID, and load your target compiled js
  - Create an fulcro client
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
      [om.next :as om]))

  (defonce app (atom (fc/new-fulcro-client :initial-state { :some-data 42 })))
  (reset! app (core/mount @app ui/Root \"app\"))
  ```

  This tiny bit of code does a *lot*:

  - It creates an entire Om ecosystem:
      - A parser for reads/mutates
      - A local read function that can read your entire app database
      - Plumbing to make it possible to do networking to your server
      - Complete handling of tempids, merging, attribute conflict resolution, and more!
      - Application locale handling
      - Support VCR viewer recording with internal mutations that can submit support requests
      - Support for refreshing the entire UI on hot code reload (with your help)
  - It mounts the application UI on the given DOM element (you can pass a real node or string ID)

  Some additional things that are available (which we'll cover soon):

  - The ability to load data on start using any queries you've placed on the UI or written elsewhere
  - The ability to do deferred lazy-load on component fields (e.g. comments on an item)

  Wow! That's a lot for two lines of code.

  ## Running Fulcro in a Dev Card

  In devcards, you can embed a full Fulcro application in a card using the macro `fulcro-app` defined in this project
  in `tutmacros.clj`. This macro creates and mounts the application for you, and accepts the same arguments as
  `new-fulcro-client`. The only exception is that initial state is passed in from the card itself:

  ```clojure
  (defcard sample
     (tm/fulcro-app ui/Root)
     { :some-data 42} ; <-- initial state from the card.
                      ; Not used if using InitialAppState (next section)
     { :inspect-data true}) ; <-- devcard options, see app state in the card!
  ```

  ## Initial Application State

  The `:initial-state` option of `new-fulcro-client` can accept a map (which will be assumed to be a TREE of non-normalized data),
  or an `atom` (which will be assumed to be a pre-normalized database).

  If you supply a map, it will be auto-normalized using your UI's query. If you supply an atom it will be used AS the
  application database.

  We do *not* recommend initializing your application in *either* of these ways except in extremely simple circumstances,
  instead Fulcro has a clever way for you to co-locate your initial app state locally on the components so that
  you just don't have to think much about it.

  You should definitely read the next section about [the InitialAppState mechanism](#!/fulcro_devguide.F_Fulcro_Initial_App_State). It
  will make your life easier.

  ")

