(ns fulcro-devguide.H20-Server-Interactions-Incremental-Loading
  (:require [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(defcard-doc
  "
  # Incremental Loading

  It is very common for your UI query to have a lot more in it than you want to load at any given time. In some cases,
  even a specific entity asks for more than you'd like to load. A good example of this is a component that allows comments.
  Perhaps you'd like the initial load of the component to not include the comments at all, then later load the comments
  when the user, for example, opens (or scrolls to) that part of the UI.

  Fulcro makes this quite easy. There are three basic steps:

  1. Put the full query on the  UI
  2. When you use that UI query with load, prune out the parts you don't want.
  3. Later, ask for the part you *do* want.

  Step 2 sounds like it will be hard, but it isn't:

  ### Pruning the Query

  Sometimes your UI graph asks for things that you'd like to load incrementally. Let's say you were loading a blog
  post that has comments. Perhaps you'd like to load the comments later:

  ```
  (df/load app :server/blog Blog {:params {:id 1 }
                                  :target [:screens/by-name :blog :current-page]
                                  :without #{:blog/comments}})
  ```

  The `:without` parameter can be used to elide portions of the query (it works recursively). The query sent to the
  server will *not* ask for `:blog/comments`. Of course, your server has to parse and honor the exact details
  of the query for this to work (if the server decides it's going to returns the comments, you get them...but this is why
  we disliked REST, right?)

  ```
  (server/defquery-root :server/blog
    (value [{:keys [query]} {:keys [id]}] ; query will be the query of Blog, without the :comments
       ; use a parser on query to get the proper blog result. See Server Interactions - Query Parsing
       (get-blog id query)))
  ```

  ### Filling in the Subgraph

  Later, say when the user scrolls to the bottom of the screen or clicks on \"show comments\" we can load the rest
  from of this previously partially-loaded graph within the Blog itself using `load-field`, which does the opposite
  of `:without` on the query:

  ```
  (defsc Blog [this props]
    {:ident  [:blog/by-id :db/id]
     :query  [:db/id :blog/title {:blog/content (prim/get-query BlogContent)} {:blog/comments (prim/get-query BlogComment)}]}
    (dom/div nil
       ...
       (dom/button #js {:onClick #(load-field this :blog/comments)} \"Show Comments\")
       ...)))
  ```

  The `load-field` function prunes everything from the query *except* for the branch
  joined through the given key. It also generates an *entity rooted query* based on the calling component's ident:

  ```
  [{[:table ID] subquery}]
  ```

  where the `[:table ID]` are the ident of the invoking component, and subquery is `(prim/get-query invoking-component)`, but
  focused down to the one field. In the example above, this would end up something like this:

  ```
  [{[:blog/by-id 1] [{:blog/comments [:db/id :comment/author :comment/body]}]}]
  ```

  This kind of query can be handled on the server with `defquery-entity` (which is triggered on these kinds of ident joins):

  ```
  (server/defquery-entity :blog/by-id
    (value [{:keys [query]} id params]
      (get-blog id query))) ; SAME HANDLER WORKS!!!
  ```

  ## What's Next?

  Now that you know how to load a graph over time, you might also want to know what to do when
  [there are errors](#!/fulcro_devguide.H30_Server_Interactions_Error_Handling).
  ")
