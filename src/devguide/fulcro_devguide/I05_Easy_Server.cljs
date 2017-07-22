(ns fulcro-devguide.I05-Easy-Server
  (:require-macros [cljs.test :refer [is]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [fulcro-devguide.state-reads.parser-1 :as parser1]
            [fulcro-devguide.state-reads.parser-2 :as parser2]
            [fulcro-devguide.state-reads.parser-3 :as parser3]
            [devcards.util.edn-renderer :refer [html-edn]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc deftest]]
            [cljs.reader :as r]
            [om.next.impl.parser :as p]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

; TODO: make external refs (e.g. to ss component) links

(defcard-doc
  "
  # Using Easy Server

  The pre-built easy server component for Fulcro uses Stuart Sierra's Component library. The server has no
  global state except for a debugging atom that holds the entire system, and can therefore be easily restarted
  with a code refresh to avoid costly restarts of the JVM.

  You should have a firm understanding of Stuart's component library, since we won't be covering that
  in detail here.

  ## Constructing a base server

  The base server is trivial to create:

  ```
  (ns app.system
    (:require
      [fulcro.easy-server :as server]
      [app.api :as api]
      [om.next.server :as om]))

  (defn make-system []
    (server/make-fulcro-server
      ; where you want to store your override config file
      :config-path \"/usr/local/etc/app.edn\"

      ; The keyword names of any components you want auto-injected into the query/mutation processing parser env (e.g. databases)
      :parser-injections #{}

      ; Additional components you want added to the server
      :components {}))
  ```

  ## Configuring the server

  Server configuration requires two EDN files:

  - `resources/config/defaults.edn`: This file should contain a single EDN map that contains
  defaults for everything that the application wants to configure.
  - `/abs/path/of/choice`: This file can be named what you want (you supply the name when
  making the server). It can also contain an empty map, but is meant to be machine-local
  overrides of the configuration in the defaults. This file is required. We chose to do this because
  it keeps people from starting the app in an unconfigured production environment.

  ```
  (defn make-system []
    (server/make-fulcro-server
      :config-path \"/usr/local/etc/app.edn\"
      ...
  ```

  The only parameter that the default server looks for is the network port to listen on:

  ```
  { :port 3000 }
  ```

  The configuration component has a number of built-in features:

  - You can override the configuration to use with a JVM option: `-Dconfig=filename`
  - The `defaults.edn` is the target of configuration merge. Your config EDN file must be a map, and anything
  in it will override what is in defaults. The merge is a deep merge.
  - Values can take the form `:env/VAR`, which will use the *string* value of that environment variable as the value
  - Values can take the form `:env.edn/VAR`, which will use `read-string` to interpret the environment variable as the value
  - Relative paths for the config file can be used, and will search the CLASSPATH resources. This allows you to package your config with you jar.

  ## Pre-installed components

  When you start an Fulcro server it comes pre-supplied with injectable components that your
  own component can depend on, as well as inject into the server-side Om parsing environment.

  The most important of these, of course, is the configuration itself. The available components
  are known by the following keys:

  - `:config`: The configuration component. The actual EDN value is in the `:value` field
  of the component.
  - `:handler`: The component that handles web traffic. You can inject your own Ring handlers into
  two different places in the request/response processing. Early or Late (to intercept or supply a default).
  - `:server`: The actual web server.

  The component library, of course, figures out the dependency order and ensures things are initialized
  and available where necessary.

  ### Making components available in the processing environment

  Any components in the server can be injected into the processing pipeline so they are
  available when writing your mutations and query procesing. Making them available is as simple
  as putting their component keyword into the `:parser-injections` set when building the server:

  ```
  (defn make-system []
    (server/make-fulcro-server
      :parser-injections #{:config}
      ...))
  ```

  ## Processing Requests

  All incoming client communication will be in the form of Om Queries/Mutations. Fulcro provides a way for you
  to hook into the processing, but you must supply the code that does the real logic.
  Learning how to build these parts is relatively simple, and is the only thing you need
  to know to process all possible communications from a client.

  If you do not supply a request parser, then the easy server defaults to using a built-in one, along with
  three macros that will generate code (`defmethod`s, actually) that will hook into just the query or
  mutation you're interested in handling.

  This is by far the easiest approach.

  The following sections use macros that are in the `fulcro.server` namespace. The macros are nice because they
  give you a bit more readability, syntax checking, and do a bit of the work for you. See Advanced
  Server Query Processing for details on more advanced query processing.

  IMPORTANT: The following only work if you do **not** define a parser for the easy server, or explicitly use
  `fulcro.server.fulcro-parser` as your server parser. Technically, they use `fulcro.server.server-read` and
  `fulcro.server.server-mutate` multimethods. So, you could directly use `defmethod` on those instead of using
  the macros.

  ### Handling a Root Query

  Given a query of the form: `[:a {:b [:x]} :c]`, the server will trigger a server-side query for each element:
  `:a`, `{:b [:x]}`, and `:c`. Each will appear as a \"root\"
  query, thus the macro name to supply the hook is `defquery-root`:

  ```
  (defquery-root :a
    \"Optional doc string\"
    (value [env params]
        ...whatever you return goes back as the response to :a...))
  ```

  There are a couple of things to notice here:

  1. You have an environment `env`. This contains *all* components that were listed in your `parser-injections` parameter. This
  is how you should obtain access to your database, config, etc. Remember, we want code reloading to work, so don't make anything
  global!
  2. You have `params`. You're probably wondering how you get these for queries!

  #### Query Parameters

  The syntax of queries technically support parameters. In the Fulcro UI we don't use them; however, when you send a request
  via load you can mix parameters into the query as a parameter to the `load` invocation:

  ```
  ; on the client
  (df/load :a A {:params {:x 1}})
  ```

  would result in a call to your `defquery-root` on `:a`, with `params` set to `{:x 1}`. Params must always be a map.

  #### Handling Joins

  When you receive a join like `{:b [:x]}` it will trigger a `defquery-root` on `:b`. The remainder of the query details
  can be found in `env`. In data-driven apps, you may have different UIs that request different amounts of data, thus
  you often will need to look at the entire query and only return to the client what is being asked for.

  The `env` will contain a few helpful things:

  - `ast` : This is an abstract-syntax tree of the query. You may wish to write transformation routines to go from this
  to a query on your database
  - `query` : This is the sub-query of root-level element (e.g. in this example it would be `[:x]`). It will be the full
  sub-graph query, which is compatible with Datomic's `pull` function. So, if you are using Datomic you can process this
  with almost no extra logic.

  ### Handling an Entity Query

  Query notation can be used to issue a join on an ident. Since an ident names both a table and ID, it implies
  a query for a specific entity (and its sub-graph). The client can issue these in at least two ways:

  ```
  ; client code. explicit ident
  (df/load component [:person/by-id 3] Person)
  ; client code. Issue a query for the containing component. Derives ident and query
  (df/refresh this)
  ```

  These will result in a query that looks like this: `[{[:person/by-id id] [:db/id :person/name]}]`. For convenience,
  the `defquery-entity` gives you easy access to the parts of this query:

  ```
  (defquery-entity :person/by-id
    \"Optional doc string\"
    (value [env id params] {:db/id id :person/name \"Joe\"}))
  ```

  You will notice that you receive the same `env` as in the other query processing, along with the `id` portion of the
  ident, and params (which can be sent with the extra parameters of `load`).

  ### Handling Mutations

  Mutations are handled identically to what you do on the server `defmutation`, except this `defmutation` comes from
  the `fulcro.server` namespace. Note that since `defmutation` places the mutations into the symbolic namespace
  in which it is declared, you will want to follow the pattern of using the same namespace on the client and server
  for the mutations:

  ```
  ; client-side: app.mutations.cljs
  (ns app.mutations
     (:require [fulcro.client.mutations :refer [defmutation]]))

  (defmutation boo ...)
  ```

  ```
  ; server-side: app.mutations.clj
  (ns app.mutations
     (:require [fulcro.server :refer [defmutation]]))

  (defmutation boo ...)
  ```

  The main difference is that `env` on the client contains the client app `state`, while the `env` on the server
  has your parser injections from server configuration. ")
