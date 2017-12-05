(ns fulcro-devguide.G-Mutation
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim]
            [fulcro.client.dom :as dom]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client :as fc]))

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

  ### The Internals

  Specifically all mutate functions must themselves be side-effect free. This is because they can be called any number
  of times (to gather up what should happen locally vs. remotely). Instead of doing the actual action, then must
  *instead* return a map that described what to do:

  ```
  (ns my-mutations
     (:require [fulcro.client.mutations :as m]))

  ; the ` quote turns it into a namespaced symbol
  (defmethod m/mutate `delete [{:keys [state ast] :as env} k params]
     {
       ; A thunk to do the *local* db modifications/optimistic update
       :action (fn []
                 (swap! state ...))

       ; if you want this mutation to also be sent to the server
       :remote true })
  ```

  This unifies server interactions and will be discussed more when we get to that topic.

  ### How You Really Write Them

  You can choose to hack in at the multimethod level, but it has the following problems:

  - You might forget to wrap your local operation in a map/action combo. This can cause strange behaviors.
  - You might side-effect outside of the action, causing unexpected behaviors
  - Your IDE/editor doesn't understand how to navigate to multimethods. This makes large programs hard to work with.
  - You cannot get docstrings on a multimethod instance. This makes it hard to get code assist when you can't quite remember the details of the mutation.

  Fulcro solves these problems by giving you a macro that makes a mutation definition look somewhat more function-like.
  It also allows you to add a docstring (that Cursive can display when you type transactions), and also give Cursive
  navigation support (you have to tell Cursive to interpret `defmutation` as `defn`).

  The macro looks like this:

  ```
  ; namespaces the symbol to the current ns automatically
  (defmutation delete [params]
    (action [env] ...)
    (remote [env] ...))

  ; honors the exact literal namespace you define. Allowed, but IDE support will suffer.
  (defmutation overridens/delete ...)
  ```

  If you don't namespace your symbol, then the macro will auto-namespace it to the current namespace. This has the
  extremely beneficial effect of enabling Cursive IDE navigation from your mutation transactions to their definition!
  See this [YouTube video](https://youtu.be/YhAOHo0CScA?t=5m45s) on Fulcro to see an example of that kind of thing in action.

  ### Locating you Mutations Elsewhere

  In general you won't write your mutations in the UI namespace. Remember that syntax quoting will honor aliasing, so
  that you can do this:

  ```
  (ns my.mutation.space
     ...)

  (defmutation do-thing ...)
  ```

  ```
  (ns my.ui.space
     (:require [my.mutation.space :as api]))

  ...
     (transact! this `[(api/do-thing {:x 1})])
  ```

  Cursive will think the mutation data is a real function call, and will jump you to the correct namespace and defmutation. It
  will also give you code assist for completion and doc strings.

  ## Example: Updating an Entity

  So, let's say your database table contains:

  ```
  { :people/by-id { 1 { :id 1 :person/name \"Joe\" }}}
  ```

  and you want to write a mutation to change the name of a person. You want to be able to say:

  ```
  (prim/transact! this `[(set-name { :person 1 :name ~n })])
  ```

  note the careful use of syntax quoting and unquote (assuming n is a local binding to the desired new name string). Remember that the
  transaction is *data*, but the data is taking a form that the compiler would love a shot at resolving!

  The use of parameters means your mutate function will receive parameters, so you can implement this with:

  ```
  (defmutation set-name [{:keys [person name]}]
    (action [{:keys [state]}]
      (swap! state update-in [:people/by-id person] assoc :person/name name)))
  ```

  Given that the rest of your database will refer to the table item, there is nothing else to do as far as the
  state goes; however, Fulcro does not do any kind of expensive overhead to figure out what changes need to be
  made in your UI as a result. The default is to just re-render the thing that *ran* the transaction (or root if
  the reconciler was used). This means there are scenarios where you have to tell Fulcro a little more for things
  to work the way you expect. We'll talk about that shortly.

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
   :table/by-id      {1 {:list-of-things [[:old-ident 1] [:old-ident 2]]}
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

  The `action` side of a mutation cannot return a value. There is no place for it to go (`transact!` queues the mutation). But you
  have access to the entire application state, so there is never really any need. You can make any change you want in a
  straight-forward manner instead of analyzing some return value after the deed is done.

  The server-side processing of a mutation *can* return a value, and there are a number of ways of dealing with that which will be discussed
  in the chapters on Server Interation.

  ## Follow-on Reads (non-local UI Refresh)

  Fulcro will refresh the component subtree after doing a mutation from wherever the transaction was run.
  You may indicate that a transaction affects *other components* by simply adding property names or idents onto
  the end of transaction to indicate what data has changed. Fulcro will find all components that query for those and re-render
  them after the update:

  ```
  (prim/transact! this `[(app/set-name { :person 1 :name ~n }) :person])
  ```

  These query keywords are known as *follow-on reads*.

  Fulcro maintains some internal indexes. One of them, `prop->classes`, is created at application startup and is an
  index of all of the properties that exist in your query, to the set of classes that have a query for them. Another
  index is a live-updating index known as `class->components` that is updated as components mount/unmount. Running a given
  property through the pair of indexes can quickly derive the full list of live components that use a particular bit of
  data.

  The beautiful thing about this is that it is data-model centric: you indicate to Fulcro what data *might* have changed,
  and it figures out what specific things to refresh in the UI. You may also list idents in the follow-on reads to force refreshes of
  all live components that have that ident.

  ```
  (prim/transact! this '[(app/do-thing) :widget [:db/id 4]])
  ```

  This facility is also why you should namespace your keywords. Indicating that `:name` changed will probably try to refresh
  a lot of stuff (panel name, person name, employee name) that didn't change.

  ## Relocating Follow-On Reads to the Mutation Itself

  In Fulcro 2.0 support was added to allow the list of things that have changed to go on the mutation itself. This moves the
  data model closer to the source of the changes, and out of the UI.

  To use the new support, simply include a `refresh` section in your mutation, like so:

  ```
  (defmutation do-thing [params]
    (action [env] ...)
    (refresh [env] [:person/name]))
  ```

  ### Refresh - Which Way???

  You should now be asking the question: Why wouldn't I just always put the follow-on reads on the mutation? Isn't it
  what is changing the data?

  Unfortunately, it is not quite that simple. It is true that the mutation *should* list the things it changes, but it
  is also true that components might appear/disappear in *other parts of the tree* due to that data change.

  Thus, *both* methods of indicating data changes are valid.

  1. Use `refresh` on the mutation for every property that it changes directly.
  2. Use follow-on reads in `transact!` to indicate data that has a UI-level dependency on that underlying change.

  In practice this is really quite simple to determine. If I have a list of items:

  - A
  - B

  and the *parent* queries for the items but displays them according to their data values (perhaps sort order?), then
  if one of the item components were to call a mutation for its value, the *item* would refresh, but Fulcro would not
  have any idea that you had a logic dependency on the parent for that data. However, you can easily know that, and include
  the data keyword in `transact`. The remaining flaw with this is that the child has to know about the parent, so it
  become a bit less reusable.

  Instead, and in general, it is recommended that parent-child relationships do `transact!` in the parent via callbacks.  It
  is very common for the parent to affect the position/visibility of the children, therefore if any edits of the children
  happen via the parent calling `transact` (which is still a nice abstraction), then there are no needs for
  the follow-on reads at all!

  In most cases this will end up being true: moving your `transact!` up a single level not only makes the reasoning
  clearer (top-down), it fixes refresh issues without resorting to follow-on reads.

  The real use-case for follow-on reads is updating distant unrelated parts of the UI, and the data-centric version (in
  the mutation) will solve that case 99% of the time.

  Do the exercises for a better understanding.

  ## Fulcro Built-in mutations

  Fulcro comes with some pre-written mutations. Some are meant for dealing with trivial kinds of UI state that isn't part
  of your persistence layer. For example, a checkbox for selecting an item for further action. That selection isn't saved
  anywhere but during that interaction with the client; however, you *do* want that data to end up in the database, so
  that things like the history viewer can see it!

  ### UI attributes

  There is a special use-case in your applications for attributes in a component query: local, UI-only data. For
  example, is a checkbox checked. Fulcro hooks this stuff up to component local state for form inputs to maintain
  stock React behavior, but that makes debugging more difficult, and it also makes some user interactions invisible
  to the support VCR viewer. Instead,
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

  ## The Complete `defmutation`

  A mutation has a local action, any number of remote interactions, and an optional refresh section:

  ```
  (defmutation do-thing
    \"Docstring\"
    [{:keys [param1] :as params}]
    (action [env] ...)
    (remote-name [env] ...)
    (remote-name-2 [env] ...)
    (refresh [env] [:kw]))
  ```

  We'll cover the remote support in the sections on server interactions. For the most part you just return true
  from one to indicate \"do this abstract thing on that server\".

  Note that the result of `defmutation` is an `addMethod` on a multimethod named `fulcro.client.mutations/mutate`...so
  nothing is actually interned into your namespace, however, the fully-qualified symbol *does* become the dispatch key
  for the multimethod.

  If you use syntax quoting on your transactions this means you can use namespace aliases to expand the symbol:
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

(defmutation sample-1 [params]
  (action [{:keys [state]}]
    (swap! state assoc :n 1)))

(defmutation ^:intern sample-2 [params]
  (action [{:keys [state]}]
    (swap! state assoc :n 1)))

(defcard-doc "
  ## Interning the Mutation

  You can also ask the macro to *actually* intern a symbol into the namespace:

  ```
  (defmutation :^intern boo ...)
  ```

  This has the normal effect (adding a method to a multimethod), but it *also* causes `boo` to appear as a real function. That
  function, if called, will do the `action` clause of the mutation. This can be handy for testing. So, `^:intern` essentially does
  this:

  ```
  (defmulti m/mutate ...) ; add the dispatch for the real mutation handling
  (defn boo [env params] ; a placeholder defn that can be used for testing, and has things like source metadata on the symbol.
    ...body of your action...)
  ```

  and now you can write tests that don't have to trigger a multimethod:

  ```
  (reset! test-state-atom {:n 2})
  (api/boo {:state test-state-atom} {:id 1})

  (is (= 3 (test-state-atom deref :n)))
  ```

  instead of:

  ```
  (reset! test-state-atom {:n 2})
  (fulcro.client.mutations/mutate {:state test-state-atom} `api/boo {:id 1})

  (is (= 3 (test-state-atom deref :n)))
  ```

  The other benefit of this is that the source of the `defmutation` has a real var, which in turn has real metadata. This
  means that tools like devcards can find the source of them for pretty-printing in documentation, and is why you
  see a lot of our mutations using the notation in this Guide!

  For example: You can embed source in devcards using `devcards.core/mkdn-pprint-source`. This function uses the metadata on symbols
  to find, read, and output the source code directly from the source file into the browser page:

  ```
  (dc/mkdn-pprint-source sample-1)
  (dc/mkdn-pprint-source sample-2)
  ```

  The output looks like the following:

  "
  (dc/mkdn-pprint-source sample-1)
  (dc/mkdn-pprint-source sample-2)
  "

  As you might have guessed: `sample-1` is not marked for `^:intern`. The second one is. If you write documentation like
  this developer's guide, this is particularly useful for keeping your documentation correct by basing it on real running
  code.

  The intern support also allows you to give the generated function an alternate name. This is rarely needed, but is again
  convenient when you want to put your client and server mutations into the same (`cljc`) file, but want to find them for extraction
  by tools like this. In that case you will need to name the mutations the same for client and server, but they cannot use the same
  names for the real definitions in the namespace! This is again mainly a devcards documentation kind of concern.
  ")

