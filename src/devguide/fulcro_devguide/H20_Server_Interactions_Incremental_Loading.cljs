(ns fulcro-devguide.H20-Server-Interactions-Incremental-Loading
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.mutations :as m]
            [fulcro.client.core :as fc]))

(defcard-doc
  "
  ### Pruning the Query

  Sometimes your UI graph asks for things that you'd like to load incrementally. Let's say you were loading a blog
  post that has comments. Perhaps you'd like to load the comments later:

  ```
  (df/load app :server/blog Blog {:params {:id 1 }
                                  :target [:screens/by-name :blog :current-page]
                                  :without #{:blog/comments}})
  ```

  The `:without` parameter can be used to elide portions of the query (it looks recursively). The query sent to the
  server will *not* ask for `:blog/comments` (which the server has to be coded to honor).

  ### Filling in the Subgraph

  Later, say when the user scrolls to the bottom of the screen or clicks on \"show comments\", we can load the rest
  from of this previously partially-loaded graph within the Blog itself:

  ```
  (defui Blog
    static prim/Ident
    (ident [this props] [:blog/by-id (:db/id props)])
    static prim/IQuery
    (query [this] [:db/id :blog/title {:blog/content (prim/get-query BlogContent)} {:blog/comments (prim/get-query BlogComment)}])
    ...
    Object
    (render [this]
      (dom/div nil
         ...
         (dom/button #js {:onClick #(load-field this :blog/comments)} \"Show Comments\")
         ...)))
  ```

  The `load-field` function does the opposite of `:without`: it prunes everything from the query *except* for the branch
  joined through the given key. It also generates an *entity rooted query* based on the calling component's ident:

  ```
  [{[:table ID] subquery}]
  ```

  where the `[:table ID]` are the ident of the invoking component, and subquery is `(prim/get-query invoking-component)`, but
  focused down to the one field. In the example above, this would end up something like this:

  ```
  [{[:blog/by-id 1] [{:blog/comments [:db/id :comment/author :comment/body]}]}]
  ```

  ## What's Next?

  Now that you know how to load a graph over time, you might also want to know what to do when
  [there are errors](#!/fulcro_devguide.H30_Server_Interactions_Error_Handling).
  ")
