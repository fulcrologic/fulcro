(ns fulcro.server-render
  (:require
    [fulcro.client.core :as fc]
    [om.next :as om]
    [fulcro.client.util :as util]))


#?(:clj
   (defn initial-state->script-tag
     "Returns a string containing an HTML script tag that that sets js/window.INITIAL_APP_STATE to a transit-encoded string version of initial-state."
     [initial-state]
     (let [assignment (str "window.INITIAL_APP_STATE = '" (util/transit-clj->str initial-state) "'")]
       (str
         "<script type='text/javascript'>\n"
         assignment
         "\n</script>\n"))))

#?(:cljs
   (defn get-SSR-initial-state
     "Obtain the value of the INITIAL_APP_STATE set from server-side rendering. Use initial-state->script-tag on the server to embed the state."
     []
     (if-let [state-string (.-INITIAL_APP_STATE js/window)]
       (util/transit-str->clj state-string)
       {:STATE "No server-side script tag was rendered from your server-side rendering."})))

(defn build-initial-state
  "This function normalizes the given state-tree using the root-component's query into standard client db format,
   it then walks the query and adds any missing data from union branches that are not the 'default' branch
   on the union itself. E.g. A union with initial state can only point to one thing, but you need the other branches
   in the normalized application database. Assumes all components (except possibly root-class) that need initial state use InitialAppState.

   Useful for building a pre-populated db for server-side rendering.

   Returns a normalized client db with all union alternates initialized to their InitialAppState."
  [state-tree root-class]
  (let [base-state (om/tree->db root-class state-tree true)
        base-state (fc/merge-alternate-union-elements base-state root-class)]
    base-state))
