(ns com.fulcrologic.fulcro.algorithms.server-render
  (:require
    [com.fulcrologic.fulcro.algorithms.transit :as transit]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalize :refer [tree->db]]
    [com.fulcrologic.fulcro.algorithms.do-not-use :refer [base64-encode base64-decode]]))

(defn initial-state->script-tag
  "Returns a *string* containing an HTML script tag that that sets js/window.INITIAL_APP_STATE to a transit-encoded string version of initial-state.

`opts` is a map to be passed to the transit writer.
`string-transform` should be a function with 1 argument. The stringified app-state is passed to it.
This is the place to perform additional string replacement operations to escape special characters,
as in the case of encoded polylines."
  ([initial-state] (initial-state->script-tag initial-state {} identity))
  ([initial-state opts] (initial-state->script-tag initial-state opts identity))
  ([initial-state opts string-transform]
   (let [state-string (-> (transit/transit-clj->str initial-state opts)
                        (string-transform)
                        (base64-encode))
         assignment   (str "window.INITIAL_APP_STATE = '" state-string "'")]
     (str
       "<script type='text/javascript'>\n"
       assignment
       "\n</script>\n"))))

#?(:cljs
   (defn get-SSR-initial-state
     "Obtain the value of the INITIAL_APP_STATE set from server-side rendering. Use initial-state->script-tag on the server to embed the state."
     ([] (get-SSR-initial-state {}))
     ([opts]
      (when-let [state-string (base64-decode (.-INITIAL_APP_STATE js/window))]
        (transit/transit-str->clj state-string opts)))))

(defn build-initial-state
  "This function normalizes the given state-tree using the root-component's query into standard client db format,
   it then walks the query and adds any missing data from union branches that are not the 'default' branch
   on the union itself. E.g. A union with initial state can only point to one thing, but you need the other branches
   in the normalized application database. Assumes all components (except possibly root-class) that need initial state use InitialAppState.

   Useful for building a pre-populated db for server-side rendering.

   Returns a normalized client db with all union alternates initialized to their InitialAppState."
  [state-tree root-class]
  (let [base-state (tree->db root-class state-tree true (merge/pre-merge-transform {}))
        base-state (merge/merge-alternate-union-elements base-state root-class)]
    base-state))

