(ns fulcro-devguide.H40-Server-Interactions-As-An-Abstraction
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [devcards.core :as dc :refer-macros [defcard-doc]]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.client.network :as net]
            [fulcro.client.util :as util]
            [fulcro.client :as fc]
            [fulcro.client.data-fetch :as df]))

(defn save [edn]
  (let [storage         js/window.localStorage
        stringified-edn (util/transit-clj->str edn)]
    (.setItem storage "edn" stringified-edn)))

(defn load []
  (let [storage         js/window.localStorage
        stringified-edn (.getItem storage "edn")]
    (or (util/transit-str->clj stringified-edn) {})))

(defrecord LocalStorageRemote [parser current-state]
  net/FulcroNetwork
  (send [this edn done-callback error-callback]
    ; the parser could have updated state, so we save it after parsing
    (let [env    {:state current-state :parser parser}
          result (parser env edn)]
      (save @current-state)
      (done-callback result edn)))
  (start [this]
    (reset! (:current-state this) (load))
    this))

(defmulti ls-read prim/dispatch)
(defmulti ls-mutate prim/dispatch)

(defmethod ls-read :some-key [{:keys [state]} k params]
  {:value (-> @state :some-key)})

(defmethod ls-mutate `set-some-key [{:keys [state]} k {:keys [value]}]
  {:action (fn [] (swap! state assoc :some-key value))})

(defmethod ls-mutate `bump-it [{:keys [state]} k {:keys [value]}]
  {:action (fn [] (swap! state update :some-key inc))})

(defmutation set-some-key [params]
  ; just note the value has changed, but don't optimistically update
  (action [{:keys [state]}] (swap! state assoc :ui/stale? true))
  ; forward the request on to the "remote"
  (local-storage [env] true))

(defmutation bump-it [params]
  ; just note the value has changed, but don't optimistically update
  (action [{:keys [state]}] (swap! state assoc :ui/stale? true))
  ; forward the request on to the "remote"
  (local-storage [env] true))

(defmutation clear-stale [params]
  (action [{:keys [state]}] (swap! state assoc :ui/stale? false)))

(defn local-storage-remote
  "Creates a remote that uses browser local storage as the back-end. The multimethods for read/write are triggered
  to process a request, and the storage value is available in an atom in the parsing env. Changes to the value
  in that atom will be auto-propagated back to the storage."
  []
  (map->LocalStorageRemote {:current-state (atom {})
                            :parser        (prim/parser {:read ls-read :mutate ls-mutate})}))

(defsc Root [this {:keys [some-key ui/react-key ui/stale?]} comp children]
  {:query         [:ui/react-key :some-key :ui/stale?]
   :initial-state {:some-key :unset :ui/stale? false}}
  (dom/div #js {:key react-key}
    (dom/p nil (str "Current value of remote value: " some-key
                 (when stale? " (stale. Use load to update.)")))
    (dom/button #js {:onClick #(df/load this :some-key nil {:remote        :local-storage
                                                            :post-mutation `clear-stale
                                                            :marker        false})} "Load the stored value")
    (dom/button #js {:onClick #(prim/transact! this `[(set-some-key {:value 1})])} "Set value to 1")
    (dom/button #js {:onClick #(prim/transact! this `[(bump-it {})])} "Increment the value")))

(defn initial-load [app]
  (df/load app :some-key nil {;:target [:my-value]
                              :remote :local-storage
                              :marker false}))



(defcard-doc
  "
  # Server interaction - As An Abstraction

  So, here's another interesting observation: the definition of a remote is simply something that can process queries and
  mutations...reads and writes. It's a generalized I/O subsystem! What's more: the subsystem can be used to isolate your
  UI from all sorts of things that are normally stateful and coded in the UI at one's peril.

  Here are some examples of abstract remotes that could be useful:

  1. Local storage: Define a remote that queries and updates a serialized EDN database in browser local storage.
  2. HTML5 History: A remote that you send mutations to in order to \"route\" to specific pages. Once started, it
  registers for the events and triggers top-level transacts on the reconciler to route the UI on history events in the browser.
  3. Datascript Integration: Datascript is a powerful database that emulates the Datomic API in the browser. While we feel it is generally
  too cumbersome to use as a UI database, it might make sense to have such a database locally that you can run queries and
  mutations against for more complex use-cases.

  Anything that you'd like to talk to through the more controlled mechanism of queued I/O communication is a potential
  candidate.

  Don't mistake this for the isolation you already get from mutations. The UI is already nicely separated from the
  implementation via the mechanism of mutations. Using remotes adds in sequential queues (which can help with asynchrony),
  and directly attaches you to the auto-normalization and merge capabilities of the server interaction mechanisms. However,
  it does require more setup code and a bit of extra code (since you have to write a mutation to trigger the interaction,
  but write the interaction separately), so it may or may not fit your needs.

  ## A Local Storage Demonstration

  Just to show the basic pattern for setting up a remote here is a sample of how to integrate with browser local storage
  through the remoting system.

  Note that this particular example would be much shorter and simpler if the remote was *not* involved, and you may share
  the opinion that find this technique adds more complexity than it is worth for this particular use-case; however, it
  is instructional with respect to how server interactions work.

  ### Persistence in Local Storage

  First, we define some simple helpers that can save and load EDN from localstorage by encoding/decoding the EDN to/from
  string form:
  "
  (dc/mkdn-pprint-source save)
  (dc/mkdn-pprint-source load)
  "

  ### Definine the Remote

  Remotes receive queries and mutations as a standard expression vector (e.g. `[(do-thing)]`). As such, we need a parser
  that can invoke our remote handlers for each element in the expression. The idea is that on start-up we'll pull the
  value from local storage and keep it in an atom (so we don't have to re-read it every time). At each interaction,
  the sequence is very simple:

  1. Create a parser environment so the handlers can see the state.
  2. Invoke the parser (which will dispatch to multimethods that can query/update the local storage state atom).
  3. Re-save the state to local storage.
  4. Call the done-callback to indicate that the request is complete, and send back the result
  "
  (dc/mkdn-pprint-source LocalStorageRemote)
  "
  In order to understand how this works you should see the rest of the setup.

  Basically, we need to create a mutltimethod for read and mutate, then a parser is created that is hooked to them."
  (dc/mkdn-pprint-source ls-read)
  (dc/mkdn-pprint-source ls-mutate)
  (dc/mkdn-pprint-source local-storage-remote)
  "
  From there, we just do `defmethod` to declare the handlers for queries and mutations against local storage (remember
  that `state` in these is the state atom in the local storage remote):

  ```
  (defmethod ls-read :some-key [{:keys [state]} k params]
    {:value (-> @state :some-key)})

  (defmethod ls-mutate `set-some-key [{:keys [state]} k {:keys [value]}]
    {:action (fn [] (swap! state assoc :some-key value))})

  (defmethod ls-mutate `bump-it [{:keys [state]} k {:keys [value]}]
    {:action (fn [] (swap! state update :some-key inc))})
  ```

  These look surprisingly familiar, because they are exactly the kinds of things you'd write when writing a server using
  multimethods (instead of the helper macros like `defquery-root`). We've defined one query and two mutations with trivial
  implementations.

  ### Setting up The Client

  The `local-storage` remote needs to be installed. If you also want server remotes, you'll have to define a map
  that includes a normal remote and also our new local storage \"remote\". The options to a new fulcro client would be:

  ```
  {:started-callback (fn [app] (initial-load app))
   :networking       {:remote        (net/make-fulcro-network \"/api\"
                                        :global-error-callback (constantly nil))
                      :local-storage (local-storage-remote)}}})
  ```

  ### Using it From The UI

  Finally, you'll see that there is nothing new here. Defining the mutations, you simply forward them on to the new
  remote using the techniques we've already discussed...just name the remote that should be used:

  ```
  (defmutation set-some-key [params]
    ; just note the value has changed, but don't optimistically update
    (action [{:keys [state]}] (swap! state assoc :ui/stale? true))
    ; forward the request on to the \"remote\"
    (local-storage [env] true))

  (defmutation bump-it [params]
    ; just note the value has changed, but don't optimistically update
    (action [{:keys [state]}] (swap! state assoc :ui/stale? true))
    ; forward the request on to the \"remote\"
    (local-storage [env] true))

  (defmutation clear-stale [params]
    (action [{:keys [state]}] (swap! state assoc :ui/stale? false)))
  ```

  Notice that we're specifically not doing an optimistic update of the value (and are instead showing a stale marker),
  so we can demonstrate more clearly that stuff is going on behind the scenes.

  ### Initial Load

  When creating the client, we'll load the local storage value via our well-know mechanism `load`. Note that we're not
  using a component, but doing so would get us auto-normalization. For this demo we're just using a scalar value.
  "
  (dc/mkdn-pprint-source initial-load)
  "
  ### Triggering Loads and Mutations

  You'll notice that there is nothing surprising here. It's just queries and mutations!

  "
  (dc/mkdn-pprint-source Root))

(defcard-fulcro local-storage-demo-card
  "# Local Storage As a Remote (Demo)

  NOTE: This demo does *not* do optimistic updates. When you click on the buttons that change state, they *only* talk
  to the remote. Clicking on the `load` button will update the UI by running a  query against our local storage simulated
  \"remote\". Reloading the browser page will also run the query on startup, which should restore the value that is in
  your current local storage.
  "
  Root
  {}
  {:fulcro {:started-callback (fn [app] (initial-load app))
            :networking       {:remote        (net/make-fulcro-network "/api"
                                                :global-error-callback (constantly nil))
                               :local-storage (local-storage-remote)}}})
