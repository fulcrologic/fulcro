(ns fulcro.server-render
  (:require
    [fulcro.client :as fc]
    [fulcro.client.primitives :as prim]
    [fulcro.client.util :as util]
    [fulcro.util :as futil]))


#?(:clj
   (defn initial-state->script-tag
     "Returns a string containing an HTML script tag that that sets js/window.INITIAL_APP_STATE to a transit-encoded string version of initial-state.

  `opts` is a map to be passed to the transit writer.
  `string-transform` should be a function with 1 argument. The stringified app-state is passed to it.
  This is the place to perform additional string replacement operations to escape special characters,
  as in the case of encoded polylines."
     ([initial-state] (initial-state->script-tag initial-state {} identity))
     ([initial-state opts] (initial-state->script-tag initial-state opts identity))
     ([initial-state opts string-transform]
      (let [state-string (-> (util/transit-clj->str initial-state opts)
                             (string-transform)
                             (util/base64-encode))
            assignment   (str "window.INITIAL_APP_STATE = '" state-string "'")]
        (str
         "<script type='text/javascript'>\n"
         assignment
         "\n</script>\n")))))

#?(:cljs
   (defn get-SSR-initial-state
     "Obtain the value of the INITIAL_APP_STATE set from server-side rendering. Use initial-state->script-tag on the server to embed the state."
     ([] (get-SSR-initial-state {}))
     ([opts]
      (when-let [state-string (util/base64-decode (.-INITIAL_APP_STATE js/window))]
        (util/transit-str->clj state-string opts)))))

(defn build-initial-state
  "This function normalizes the given state-tree using the root-component's query into standard client db format,
   it then walks the query and adds any missing data from union branches that are not the 'default' branch
   on the union itself. E.g. A union with initial state can only point to one thing, but you need the other branches
   in the normalized application database. Assumes all components (except possibly root-class) that need initial state use InitialAppState.

   Useful for building a pre-populated db for server-side rendering.

   Returns a normalized client db with all union alternates initialized to their InitialAppState."
  [state-tree root-class]
  (let [base-state (prim/tree->db root-class state-tree true (prim/pre-merge-transform {}))
        base-state (fc/merge-alternate-union-elements base-state root-class)]
    base-state))

#?(:cljs
   (defn defer-until-network-idle
     "Schedule a function to run when all remotes (or the specified one) have become idle.  The function will not
      be called on transition edges (idle but outstanding queued items).  This function is meant for scheduling
      server-side rendering in `node.js` when you're running a real client against loopback remotes in order to
      get pre-rendered html.  Of course, it can only detect network activity on remotes the reconciler controls.
      Side-band networking through external APIs is not detected.

      This function is not meant for general-purpose use in a client and is not supported for anything but SSR
      in node.  Using it in other contexts should work, but are discouraged as a poor pattern for Fulcro."
     ([reconciler callback]
      (defer-until-network-idle reconciler callback nil))
     ([reconciler callback remote]
      (let [network-activity (prim/get-network-activity reconciler)
            watch-key        (futil/unique-key)
            networks-active? (if remote
                               #(= :active (get-in % [remote :status]))
                               #(->> % vals (map :status) (some #{:active}) boolean))]
        (if-not (networks-active? @network-activity)
          (callback)
          (add-watch network-activity watch-key
            (fn [key atom _ new-state]
              (when-not (networks-active? new-state)
                (remove-watch atom key)
                (callback)))))))))
