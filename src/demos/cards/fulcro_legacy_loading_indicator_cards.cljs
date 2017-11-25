(ns cards.fulcro-legacy-loading-indicator-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [recipes.lazy-loading-visual-indicators-client :as client]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [fulcro.client.dom :as dom]
    [fulcro.client.impl.protocols :as p]
    [fulcro.client.primitives :as prim]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
    [fulcro.client.primitives :as prim]))


(dc/defcard-doc
  "# Lazy Load Indicators (Legacy)

  NOTE: Fulcro 2.0 has an improved version of this. See the Developer's Guide for more details. Feel free to contribute
  a patch to this demo!

  Fulcro places markers on items that are being loaded. These markers can be used to show progress indicators in
  the UI. There are essentially two kinds: a global marker, and an item-based marker. The global marker is present during
  and loads, whereas the localized markers are present until a specific item's load has completed.

  The comments in the code below describe how to use these:
  "
  (dc/mkdn-pprint-source client/Item)
  (dc/mkdn-pprint-source client/Child)
  (dc/mkdn-pprint-source client/Root))

(defcard-fulcro lazy-loading-demo
  "
  # Demo

  This is a full-stack demo, and requires you run the server (see demo instructions).

  The first button triggers a load of a child's data from the server. There is a built-in delay of 1 second so you
  can see the markers. Once the child is loaded, a button appears indicating items can be loaded into that child. The
  same 1 second delay is present so you can see the markers.

  Once the items are loaded, each has a refresh button. Again, a 1 second delay is present so you can examine the
  markers.

  The app state is shown so you can see the marker detail appear/disappear. In general you'll use the `lazily-loaded`
  helper to render different load states, and you should not base you code on the internal details of the load marker data.

  Note that once you get this final items loaded (which have refresh buttons), the two items have different ways of
  showing refresh.
  "
  client/Root
  {}
  {:inspect-data true})

(comment
  (def idx (-> lazy-loading-demo-fulcro-app deref :reconciler prim/get-indexer))
  (p/key->components idx :ui.fulcro.client.data-fetch.load-markers/by-id)
  )
