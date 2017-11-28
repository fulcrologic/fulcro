(ns cards.server-return-values-manually-merging
  (:require
    #?@(:clj  []
        :cljs [[devcards.core :as dc :include-macros true]
               [fulcro.client.cards :refer [fulcro-app]]])
    [fulcro.client.dom :as dom]
    [fulcro.server :as server]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.logging :as log]
    [fulcro.util :as util]
    [fulcro.client.mutations :as m]
    [fulcro.client.primitives :as prim :refer [defui]]
    [fulcro.client.core :as fc :refer [InitialAppState initial-state]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(server/defmutation crank-it-up [{:keys [value]}]
  (action [env]
    #?(:clj (Thread/sleep 200))                             ; simulate a bit of delay
    (inc value)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLIENT:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti merge-return-value (fn [state sym return-value] sym))

; Do all of the work on the server.
(m/defmutation crank-it-up [params]
  (remote [env] true))

(defmethod merge-return-value `crank-it-up
  [state _ {:keys [value]}]
  (assoc-in state [:child/by-id 0 :volume] value))

(defui ^:once Child
  static InitialAppState
  (initial-state [cls params] {:id 0 :volume 5})
  static prim/IQuery
  (query [this] [:id :volume])
  static prim/Ident
  (ident [this props] [:child/by-id (:id props)])
  Object
  (render [this]
    (let [{:keys [id volume]} (prim/props this)]
      (dom/div nil
        (dom/p nil "Current volume: " volume)
        (dom/button #js {:onClick #(prim/transact! this `[(crank-it-up ~{:value volume})])} "+")))))

(def ui-child (prim/factory Child))

(defui ^:once Root
  static InitialAppState
  (initial-state [cls params]
    {:child (initial-state Child {})})
  static prim/IQuery
  (query [this] [:ui/react-key {:child (prim/get-query Child)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key child]} (prim/props this)]
      (dom/div #js {:key react-key} (ui-child child)))))

#?(:cljs
   (dc/defcard mutation-return-value-card
     "
     # Mutation Return Values

     NOTE: In most cases you're better off using Mutation Joins instead of this. See the Developer's Guide and
     the demo of Mutation Joins in these demos for more information.

     This is a full-stack example. Make sure you're running the server and are serving this page from it. The
     displayed volume in the UI is coming from the server's mutation return value.

     There is a bit of simulated delay on the server (200ms). Notice if you click too rapidly then the value doesn't increase
     any faster than the server can respond (since it computes the new volume based on what the client sends). Opening
     the console in your devtools on the browser where you can see the transaction makes this easier to follow and understand.
     "
     (fulcro-app Root :mutation-merge merge-return-value)
     {}
     {:inspect-data false}))

#?(:cljs
   (dc/defcard-doc
     "# Explanation

     Occasionally a full-stack application will want to return a value from the server-side of a mutation. In general this
     is rarer that in other frameworks because most operations in Fulcro are done optimistically (the UI drives the changes).

     The pipeline normally throws away return values from server-side mutations, but does provide a way to
     make use of them. Fulcro hooks into this functionality and provides a simple way to interface with the return
     values.

     Note that Fulcro specifically avoids asychrony whenever possible. Networking is, by nature, async in the browser. You
     application could be off doing anything at all by the time the server responds to a network request. Therefore, return
     values from mutations will have an async nature to them (they arrive when they arrive).

     Fulcro's method of allowing you to recieve a return value is for you to provide a function that can merge the return
     value into your app database when it arrives. Most commonly you'll do this by defining a multimethod that looks
     very similar to a mutation multimethod, and dispatches the same way:

     "
     (dc/mkdn-pprint-source merge-return-value)
     "

     You can then define methods for each mutation symbol. For example, the demo has a `crank-it-up` mutation, which
     does no optimistic update. It simply sends it off to the server for processing.

     ```
     (defmethod m/mutate `crank-it-up [env k params] {:remote true})
     ```

     Our mutation asks a remote server to increase the volume. It does the computation and returns the new value
     (in this case based on the old value from the UI). It need only return a value to have it propagate to the client:

     ```
     (server/defmutation crank-it-up [{:keys [value]}]
       (action [env] (inc value)))
     ```

     Handling the return value involves defining a method for that dispatch:

     ```
     (defmethod merge-return-value `crank-it-up [state _ {:keys [value]}]
       (assoc-in state [:child/by-id 0 :volume] value))
     ```

     The remainder of the setup is just giving the merge handler function to the application at startup:

     ```
     (fc/new-fulcro-client :mutation-merge merge-return-value)
     ```

     The UI code for the demo is:
     "
     (dc/mkdn-pprint-source Child)
     (dc/mkdn-pprint-source Root)))


