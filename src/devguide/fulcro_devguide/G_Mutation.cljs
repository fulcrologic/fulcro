(ns fulcro-devguide.G-Mutation
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.core :as fc]))

(defcard-doc
  "
  # Mutation

  This section covers local app state modification. Any modifications that need to interact with the server
  are covered in the remote mutation section.

  ## Overview

  Mutations are part of the query syntax and are invoked with `transact!`.
  The mutations themselves are meant to be thought of as top-level transactions
  which are abstract operations over your application state that can be serialized for history or even sent across one or
  more networks to affect remote changes. The difference between a local mutation and a remote one is
  indistinguishable in the UI code itself. So, assume you want to be able to delete something in the UI, you might
  call:

  ```
  (prim/transact! this-component '[(app/delete)])
  ```

  Of course, something has to descibe *how* to do this operation. Internally, mutations are dispatched to a multimethod
  called `fulcro.client.mutations/mutate`. In order to generate a clean overall operational model, there are some rules
  around how this function must work:

  Specifically all mutate functions must themselves be side-effect free. This is because they can be called any number
  of times (to gather up what should happen locally vs. remotely). Instead of doing the actual action, then must
  *instead* return a map that described what to do:

  ```
  (ns my-mutations
     (:require [fulcro.client.mutations :as m]))

  (defmethod m/mutate 'app/delete [{:keys [state ast] :as env} k params]
     {
       ; A thunk to do the *local* db modifications/optimistic update
       :action (fn []
                 (swap! state ...))

       ; if you want this mutation to also be sent to the server
       :remote true })
  ```

  This unifies server interactions and will be discussed more when we get to that topic.

  Fulcro has a macro that can write this defmethod for you, and it is a little less error prone:

  ```
  (defmutation app/delete [params]
    (action [env] ...)
    (remote [env] ...))
  ```

  If you don't namespace your symbol, then the macro will auto-namespace it to the current namespace. This has the
  extremely beneficial effect of enabling Cursive IDE navigation from your mutation transactions to their definition!
  See this [YouTube video](https://youtu.be/YhAOHo0CScA?t=5m45s) on Fulcro to see an example of that kind of thing in action.

  ## Updating an item stored in a map

  So, let's say your database table contains:

  ```
  { :people/by-id { 1 { :id 1 :person/name \"Joe\" }}}
  ```

  and you want to write a mutation to update the name. One possibility is (assuming the multimethod approach above)
  would be to invent a top-level transaction that is used like this:

  ```
  (prim/transact! this `[(app/set-name { :person 1 :name ~n })])
  ```

  note the careful use of syntax quoting and unquote (assuming n is a local binding to the desired new name string). Remember that the
  transaction is *data*, but the data is taking a form that the compiler would love a shot at resolving!

  The use of parameters means your mutate function will receive parameters, so you can implement this with:

  ```
  (defmethod m/mutate 'app/set-name
    [{:keys [state] :as env} key {:keys [person name] :as params}]
    {:action (fn []
      (swap! state update-in [:people/by-id person] assoc :person/name name))})
  ```

  or

  ```
  (defmutation app/set-name [{:keys [person name]}]
    (action [{:keys [state]}]
      (swap! state update-in [:people/by-id person] assoc :person/name name)))
  ```

  Given that the rest of your database will refer to the table item, there is nothing else to do as far as the
  state goes; however, Fulcro does not do any kind of expensive overhead to figure out what changes need to be
  made in your UI as a result. The default is to just re-render the thing that *ran* the transaction (or root if
  the reconciler was used).

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

  You'll need to do the data manipulations to make it look right. For example, to add \"Tom\" to your friends,
  your mutation action thunk would basically need to do:

  ```
  (swap! state update :people/friends conj [:people/by-id 3])
  ```

  resulting in the following app database state:

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

  resulting in the following app database state:

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
         (fc/integrate-ident! state [:new-ident (rand-int 100000)]
           :append [:table/by-id 1 :list-of-things])
         ```
         "
         (fn [state _]
           (dom/div nil
             (dom/button #js {:onClick #(fc/integrate-ident! state [:new-ident (rand-int 100000)]
                                                             :append [:table/by-id 1 :list-of-things]
                                                             )} "Append a random ident")))
         {:table/by-id {1 {:list-of-things []}}}
         {:inspect-data true})

(defcard integrate-ident-prepend
         "You can also use it to prepend. It will refuse to prepend a duplicate.

         ```
         (fc/integrate-ident! state [:new-ident (rand-int 100000)]
           :prepend [:table/by-id 1 :list-of-things])
         ```
         "
         (fn [state _]
           (dom/div nil
             (dom/button #js {:onClick #(fc/integrate-ident! state [:new-ident (rand-int 100000)]
                                                             :prepend [:table/by-id 1 :list-of-things]
                                                             )} "Prepend a random ident")))
         {:table/by-id {1 {:list-of-things []}}}
         {:inspect-data true})

(defcard integrate-ident-replace
         "You can use it to replace an item. The target MUST already exist, and can be a to-one or to-many.

         ```
         (fc/integrate-ident! state [:new-ident (rand-int 100000)]
           :replace [:table/by-id 1 :list-of-things 0])
         ```
         "
         (fn [state _]
           (dom/div nil
             (dom/button #js {:onClick #(fc/integrate-ident! state [:new-ident (rand-int 100000)]
                                                             :replace [:table/by-id 1 :list-of-things 0]
                                                             )} "Replace first with a random ident")))
         {:table/by-id {1 {:list-of-things [[:old-ident 1] [:old-ident 2]]}}}
         {:inspect-data true})

(defcard integrate-ident-combo
         "The function allows you to specify as many operations as you need to do at once.

         ```
         (fc/integrate-ident! state [:new-ident (rand-int 100000)]
           :append [:table/by-id 1 :list-of-things]
           :prepend [:table/by-id 2 :list-of-things]
           :replace [:the-thing-I-like]
           :replace [:table/by-id 3 :list-of-things 0])
         ```
         "
         (fn [state _]
           (dom/div nil
             (dom/button #js {:onClick #(fc/integrate-ident! state [:new-ident (rand-int 100000)]
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
  the power of data structure tools in cljs, it is simple enough to scan the database for idents (e.g. using
  `clojure.walk/prewalk`), and then remove anything from the tables that are not in this collected set of idents.

  ## Returning a Value From a Mutation

  In general it is not useful to return a value from a mutation. There is nothing you can do with the value. A mutation
  is already an action on state. So, you just act on state.

  In the case of a remote mutation we also mostly recommend avoiding it, but it is possible to hook into the network
  processing and handle return value from mutations. We'll talk about that when we talk about server interactions.

  ## Follow-on Reads (non-local UI Refresh)

  After doing a mutation Fulcro will refresh the component subtree from whereever the transaction was run.
  To indicate that a transaction affects *other components*, you simply tack simple property names onto
  the end of transaction to indicate what data changes. Fulcro will find all components that query for those and re-render
  them after the update:

  ```
  (prim/transact! this `[(app/set-name { :person 1 :name ~n }) :person])
  ```

  These query keywords are known as *follow-on reads*.

  Fulcro maintains some internal indexes. One of them, `prop->classes`, is created at application startup and is an
  index of all of the properties that exist in your query, to the set of classes that have a query for them. Another
  index is a live updating index `class->components` that is updated on component mount/unmount. Running a given
  property through the pair of indexes can quickly derive the full list of live components that need a refresh.

  The beautiful thing about this is that it is data-model centric: you indicate to Fulcro what data *might* have changed,
  and it figures out what specific things to refresh in the UI. You may also list idents in the follow-on reads to force refreshes of
  all live components that have that ident.

  ```
  (prim/transact! this '[(app/do-thing) :widget [:db/id 4]])
  ```

  This facility is also why you should namespace your keywords. Indicating that `:name` changed will probably try to refresh
  a lot of stuff (panel name, person name, employee name) that didn't change.

  ## Fulcro built-in mutations

  ### UI attributes

  There is a special use-case in your applications for attributes in a component query: local, UI-only data. For
  example, is a checkbox checked. Om generally hooks this stuff up to component local state, but that makes
  debugging more difficult, and it also makes some user interactions invisible to the support VCR viewer. Instead,
  if you namespace these UI-only attrubutes to `ui`, they will be elided from server queries (see Server Interactions).

  Since UI attributes really don't need very abstract mutations (typical operations are 'set to this string' and 'toggle')
  Fulcro includes these mutations, along with convenience functions for easy IDE use.

  The functions of interest are in `fulcro.client.mutations` (`ucm` below):

  - `(ucm/toggle! this :ui/visible)` - Change the :ui/visible property on this component in the app state by toggling it between true/false.
  - `(ucm/set-string! this :ui/value :value \"hello\")` - Change :ui/value on this component to a literal value
  - `(ucm/set-string! this :ui/value :event evt)` - Same, but extract evt.target.value from the provided js input event and use that.
  - `(ucm/set-integer! this :ui/value :value \"33\")` - Change :ui/value on this component, but first coerce it to an integer
  - `(ucm/set-integer! this :ui/value :event evt)` - Same, but extract evt.target.value from the provided js input event and use that.

  IMPORTANT NOTE: This component (`this`) *MUST* have an ident (which is how the mutations find it in the app state).

  ## Making Mutations Nicer to Work With

  Fulcro defines a macro named `defmutation` that emits the multimethods shown above. The syntax prevents some kinds
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
  (prim/transact! this `[(x/do-thing {:param1 1})])
  ```

  and the compiler will expand it to

  ```
  (prim/transact! this '[(x.y.z/do-thing {:param1 1})])
  ```

  Not only is this useful in keeping your mutations from colliding with each other, it saves you typing and also
  enables the IDE to see the mutation as a jump target. To enable this, click on any `defmutation` (the macro name itself) and wait
  for a light-bulb to appear, then click on it and select \"Resolve defmutation as defn\" and the IDE will start treating your
  mutations as if they were real functions (even though they are just add-ins to a multimethod).
  ")

