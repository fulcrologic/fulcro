(ns untangled-devguide.G-Mutation
  (:require-macros [cljs.test :refer [is]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [untangled.client.core :as uc]))

(defcard-doc
  "
  # Mutation

  This section covers local app state modification. Any modifications that need to interact with the server
  are covered in the remote mutation section.

  ## Overview


  Mutations are part of the query syntax, except they are invoked with `transact!` and dispath to your top-level
  mutation function instead of the read function. The mutations are meant to be thought of as top-level transactions
  which are abstract operations over your application state. The difference between a local mutation and a remote one is
  indistinguishable in the UI code itself. So, assume you want to be able to delete something in the UI, you might
  call:

  ```
  (om/transact! this-component '[(app/delete)])
  ```

  Untangled reduces the amount of boilerplate you have to write since there is no need for a custom
  Om parser. As such, to support a new abstract mutation you simply add methods to the
  `untangled.client.mutations/mutate` multimethod.

  Your mutate function must be side-effect free so instead of doing the actual action, it must
  return a map that contain instructions about what to do:

  ```
  (ns my-mutations
     (:require [untangled.client.mutations :as m]))

  (defmethod m/mutate 'app/delete [{:keys [state ast] :as env} k params]
     {
       ; A thunk to do the local db modifications/optimistic update
       :action (fn []
                 (swap! state ...))

       ; if you want this mutation to also be sent to the server
       :remote true })
  ```

  It is also possible to change the form of the mutation that is sent to the server (the code above causes
  the identical mutation to be sent to the server that was initiated by the client code).

  ## Updating an item stored in a map

  So, let's say your database table contains:

  ```
  { :people/by-id { 1 { :id 1 :person/name \"Joe\" }}}
  ```

  and you want to write a mutation to update the name. One possibility is (assuming the multimethod approach above)
  would be to invent a top-level transaction that is used like this:

  ```
  (om/transact! this `[(app/set-name { :person 1 :name ~n })])
  ```

  note the careful use of syntax quoting and unquote (assuming n is a local binding to the desired new name string).

  The use of parameters means your mutate function will receive parameters, so you can implement this with:

  ```
  (defmethod m/mutate 'app/set-name
    [{:keys [state] :as env} key {:keys [person name] :as params}]
    {:action (fn []
      (swap! state update-in [:people/by-id person] assoc :person/name name))})
  ```

  Given that the rest of your database will refer to the table item, there is nothing else to do as far as the
  mutation goes. To indicate that a transaction affects other components you can tack property names onto
  the transaction to indicate that any component that queries for the given property will be re-rendered
  after the update:

  ```
  (om/transact! this `[(app/set-name { :person 1 :name ~n }) :person])
  ```

  You should understand that all components that include that property name in their query will be
  re-rendered. More details can be found below.

  ## Adding an item to a list

  There are two cases for adding an item to a list: The item is already in the database (in a table), in which
  case you just need to append (or otherwise insert) the ident for that item in the list. If the item is not in
  a table, then you'll have to add it to the table and then put it in the list. Here is a specific example:

  Given the database:

  ```
  { :people/friends [ [:people/by-id 1] [:people/by-id 2] ]
    :people/by-id { 1 { :id 1 :person/name \"Joe\" }
                    2 { :id 2 :person/name \"Sally\" }
                    3 { :id 3 :person/name \"Tom\" }
                    4 { :id 4 :person/name \"May\" }}}
  ```

  You'll need to simply do the data manipulations to make it look right. For example, to add \"Tom\" to your friends,
  your mutation action thunk would basically need to do:

  ```
  (swap! state update :people/friends conj [:people/by-id 3])
  ```

  ... resulting in the following app database state:

  ```
  { :people/friends [ [:people/by-id 1] [:people/by-id 2] [:people/by-id 3] ]
    :people/by-id { 1 { :id 1 :person/name \"Joe\" }
                    2 { :id 2 :person/name \"Sally\" }
                    3 { :id 3 :person/name \"Tom\" }
                    4 { :id 4 :person/name \"May\" }}}
  ```


  To add a brand new person and make them a friend, you'd need to add them to the `:people/by-id` table and to the
  `:people/friends` list with code like this:

  ```
  (swap! state assoc-in [:people/by-id 7] {:id 7 :person/name \"Andy\"})
  (swap! state update :people/friends conj [:people/by-id 7])
  ```

  ... resulting in the following app database state:

  ```
  { :people/friends [ [:people/by-id 1] [:people/by-id 2] [:people/by-id 3] [:people/by-id 7] ]
    :people/by-id { 1 { :id 1 :person/name \"Joe\" }
                    2 { :id 2 :person/name \"Sally\" }
                    3 { :id 3 :person/name \"Tom\" }
                    4 { :id 4 :person/name \"May\" }
                    7 { :id 7 :person/name \"Andy\" }}}
  ```

  ### Using `integrate-ident!`

  There is a helper function in the core library that can help with the operations we're describing here: `integrate-ident!`.
  This function can work directly on app state to append, replace, or prepend idents in your application database. It
  is probably simplest to just show some examples via cards:
  "
  )

(defcard integrate-ident-append
         "You can use the function to append an ident to an existing list of idents anywhere in you app state (by
         update-in path). Append will refuse to append a duplicate.

         ```
         (uc/integrate-ident! state [:new-ident (rand-int 100000)]
           :append [:table/by-id 1 :list-of-things])
         ```
         "
         (fn [state _]
           (dom/div nil
             (dom/button #js {:onClick #(uc/integrate-ident! state [:new-ident (rand-int 100000)]
                                                             :append [:table/by-id 1 :list-of-things]
                                                             )} "Append a random ident")))
         {:table/by-id {1 {:list-of-things []}}}
         {:inspect-data true})

(defcard integrate-ident-prepend
         "You can also use it to prepend. It will refuse to prepend a duplicate.

         ```
         (uc/integrate-ident! state [:new-ident (rand-int 100000)]
           :prepend [:table/by-id 1 :list-of-things])
         ```
         "
         (fn [state _]
           (dom/div nil
             (dom/button #js {:onClick #(uc/integrate-ident! state [:new-ident (rand-int 100000)]
                                                             :prepend [:table/by-id 1 :list-of-things]
                                                             )} "Prepend a random ident")))
         {:table/by-id {1 {:list-of-things []}}}
         {:inspect-data true})

(defcard integrate-ident-replace
         "You can use it to replace an item. The target MUST already exist, and can be a to-one or to-many.

         ```
         (uc/integrate-ident! state [:new-ident (rand-int 100000)]
           :replace [:table/by-id 1 :list-of-things 0])
         ```
         "
         (fn [state _]
           (dom/div nil
             (dom/button #js {:onClick #(uc/integrate-ident! state [:new-ident (rand-int 100000)]
                                                             :replace [:table/by-id 1 :list-of-things 0]
                                                             )} "Replace first with a random ident")))
         {:table/by-id {1 {:list-of-things [[:old-ident 1] [:old-ident 2]]}}}
         {:inspect-data true})

(defcard integrate-ident-combo
         "The function allows you to specify as many operations as you need to do at once.

         ```
         (uc/integrate-ident! state [:new-ident (rand-int 100000)]
           :append [:table/by-id 1 :list-of-things]
           :prepend [:table/by-id 2 :list-of-things]
           :replace [:the-thing-I-like]
           :replace [:table/by-id 3 :list-of-things 0])
         ```
         "
         (fn [state _]
           (dom/div nil
             (dom/button #js {:onClick #(uc/integrate-ident! state [:new-ident (rand-int 100000)]
                                                             :append [:table/by-id 3 :list-of-things]
                                                             :prepend [:table/by-id 2 :list-of-things]
                                                             :replace [:the-thing-I-like]
                                                             :replace [:table/by-id 1 :list-of-things 0])
                              } "Do a bunch all at once!")))
         {:the-thing-I-like [:thing 1]
          :table/by-id {1 {:list-of-things [[:old-ident 1] [:old-ident 2]]}
                        2 {:list-of-things [[:old-ident 9] [:old-ident 44]]}
                        3 {:list-of-things [[:old-ident 98] [:old-ident 99]]}}}
         {:inspect-data true})

(defcard-doc

  "
  ## Deleting things

  Deleting things can include two possible steps: removing the ident that refers to the table (e.g. from the
  `:people/friends` list) and removing the actual data from the table itself.

  The primary thing to be careful about is not to remove something from the database table that you are
  still referring to in another part of the UI.

  This means that your approach may be to leave the items in the top-level tables to avoid possible bugs when
  removing things, but then it might be necessary to implement some form of garbage collection on your tables. Given
  the power of data structure tools in cljs, it is simple enough to scan the database for idents, and then remove
  anything from the tables that are not in this collected set of idents.

  ## Returning a Value From a Mutation

  We included this section because it is a common question. The answer is \"you can't\". On the surface this seems like
  a problem, but if you think about the overall model it becomes quite evident:

  - Which mutation return value do you get (there could be a local AND remote)?
  - What should the mutation return? The server version has no idea what is on your UI, so how can it decide what updated
  facts you need?
  - Where would the return value go? Transact can be reasoned about asynchronously, but you're in browser land. It isn't.
  You cannot capture it synchronously. We could give you callback madness, but we're here to free you from that. Finally,
  there is no query, and as you saw in many other places, we cannot merge data to the database correctly without one!

  There is an exception to this rule: mutations the are run remotely can return a value, but it is ignored unless you
  plug into the merge routines and handle it. This is an advanced option covered in
  [Server Interactions](#!/untangled_devguide.H_Server_Interactions).

  ## Details on refreshing components after mutation

  After doing a mutation, you can trigger re-renders by listing query bits after the mutation. Any keywords you list
  will trigger re-renders of things that queried for those keywords. Any idents (e.g. `[:db/id 4]`) will trigger
  re-renders of anything that has that Ident. In the example below, anything that has included the prop named
  `:widget` or has the Ident `[:db/id 4]` will re-render after the operation.

  ```
     (om/transact! this '[(app/do-thing) :widget [:db/id 4]])
  ```

  At first, this might seem like overkill (lots of different components could have mentioned `:widget`. This is
  part of the motivation behind namespacing property keywords. It is not required, but it helps prevent refreshing
  components that don't need it.

  This mechanism works as follows (basically):

  Any keywords mentioned in the transaction are used to look up components (via the internal indexer). Those
  components are used to transform the keywords requested into full queries to run against the local app state. Those
  queries are run, the results are focused to the target components, and those components are re-rendered. Of course
  if the state hasn't changed, then React will optimize away any actual DOM change.

  ## Untangled built-in mutations

  ### UI attributes

  There is a special use-case in your applications for attributes in a component query: local, UI-only data. For
  example, is a checkbox checked. Om generally hooks this stuff up to component local state, but that makes
  debugging more difficult, and it also makes some user interactions invisible to the support VCR viewer. Instead,
  if you namespace these UI-only attrubutes to `ui`, they will be elided from server queries (see Server Interactions).

  Since UI attributes really don't need very abstract mutations (typical operations are 'set to this string' and 'toggle')
  Untangled includes these mutations, along with convenience functions for easy IDE use.

  The functions of interest are in `untangled.client.mutations` (`ucm` below):

  - `(ucm/toggle! this :ui/visible)` - Change the :ui/visible property on this component in the app state by toggling it between true/false.
  - `(ucm/set-string! this :ui/value :value \"hello\")` - Change :ui/value on this component to a literal value
  - `(ucm/set-string! this :ui/value :event evt)` - Same, but extract evt.target.value from the provided js input event and use that.
  - `(ucm/set-integer! this :ui/value :value \"33\")` - Change :ui/value on this component, but first coerce it to an integer
  - `(ucm/set-integer! this :ui/value :event evt)` - Same, but extract evt.target.value from the provided js input event and use that.

  IMPORTANT NOTE: This component (`this`) *MUST* have an ident (which is how the mutations find it in the app state).

  ## Making Mutations Nicer to Work With

  Untangled defines a macro named `defmutation` that emits the multimethods shown above. The syntax prevents some kinds
  of errors (accidentally doing an action outside of a function), and also leads to better integration with the Cursive
  IDE. It looks like this:

  ```
  (defmutation do-thing
    \"Docstring\"
    [{:keys [param1] :as params}]
    (action [env] ...)
    (remote-name [env] ...))
  ```

  It uses the namespace of declaration as the namespace of the symbol for `do-thing` (even though it is just a symbol
  and isn't interned into the actual namespace).

  If you use syntax quoting on your transactions, this means you can use namespace aliases to expand the symbol:
  For example, if you have required namespace `[x.y.z :as x]`, and `do-thing` is declared in that namespace
  then you can write
  ```
  (om/transact! this `[(x/do-thing {:param1 1})])
  ```

  and the compiler will expand it to

  ```
  (om/transact! this '[(x.y.z/do-thing {:param1 1})])
  ```

  Not only is this useful in keeping your mutations from colliding with each other, it saves you typing and also
  enables the IDE to see the mutation as a jump target. To enable this, click on any `defmutation` (the macro name itself) and wait
  for a light-bulb to appear, then click on it and select \"Resolve defmutation as defn\" and the IDE will start treating your
  mutations as if they were real functions (even though they are just add-ins to a multimethod).
  ")

