(ns fulcro-devguide.I-Building-A-Server
  (:require [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.primitives :as prim]
            [fulcro.client :as fc]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.client.dom :as dom]))

(defcard-doc
  "
  # Building a Server - More Details

  Fulcro comes with pre-built server components. These allow you to very quickly get the server side of your
  application up and running. We'll talk about three ways to do this:

  - How to use the pre-built bits to manually build your own server.
  - A one-liner that will work to get started, and possibly even into production
  - A more involved pre-written, but modular approach, that has more overall flexibility.

  The are referred to as the \"easy\" and \"modular\" servers respectively.

  ## Rolling Your Own Server

  If you're integrating with an existing server then you probably just want to know how to get things working without
  having to use a Component library, and all of the other stuff that comes along with it.

  It turns out that the server API handling is relatively light. Most of the work goes into getting things set up
  for easy server restart (e.g. making components stop/start) and getting those components into your parsing environment.

  If you have an existing server, then you've mostly figured out all of that stuff already, and just want to plug
  a Fulcro API handler into it.

  Here are the basic requirements:

  1. Make sure your Ring stack has transit-response and transit-params. You can see the a sample Ring stack in
  [Fulcro's source](https://github.com/fulcrologic/fulcro/blob/1.2.0/src/main/fulcro/easy_server.clj#L94)
  2. Check to see if the incoming request is for \"/api\". If so:
  3. Call [`handle-api-request`](https://github.com/fulcrologic/fulcro/blob/1.2.0/src/main/fulcro/server.clj#L354). Pass
  a parser to it (recommend using `fulcro.server/fulcro-parser`), an environment, and the EDN of the request. It will
  give back a Ring response.

  You're responsible for creating the parser environment. I'd recommend using
  the `fulcro.server/fulcro-parser` because it is already hooked to the multimethods that the dev guide talks about
   for handling server requests at the API level like `defquery-root`. Those won't work unless you use it, but any parser
  that can deal with the query/mutation syntax is technically legal.

  Here's a crappy little server with no configuration support, no ability to hot code reload, and no external
  integrations at all. But it shows how little you need:

  ```
  (ns solutions.tiny-server
    (:require
      [ring.middleware.content-type :refer [wrap-content-type]]
      [ring.middleware.gzip :refer [wrap-gzip]]
      [ring.middleware.not-modified :refer [wrap-not-modified]]
      [ring.middleware.resource :refer [wrap-resource]]
      [ring.util.response :as rsp :refer [response file-response resource-response]]
      [org.httpkit.server]
      [fulcro.server :as server]))

  (defn not-found-handler []
    (fn [req]
      {:status  404
       :headers {\"Content-Type\" \"text/plain\"}
       :body    \"NOPE\"}))

  (def parser (server/fulcro-parser))

  (defn wrap-api [handler uri]
    (fn [request]
      (if (= uri (:uri request))
        (server/handle-api-request parser {} (:transit-params request))
        (handler request))))

  (defn my-tiny-server []
    (let [port       9002
          ring-stack (-> (not-found-handler)
                       (wrap-api \"/api\")
                       (server/wrap-transit-params)
                       (server/wrap-transit-response)
                       (wrap-resource \"public\")
                       (wrap-content-type)
                       (wrap-not-modified)
                       (wrap-gzip))]
      (org.httpkit.server/run-server ring-stack {:port port})))
  ```

  In a REPL, you could start this one up with:

  ```
  solutions.tiny-server=> (my-tiny-server)
  ```

  This source is already `src/devguide/solutions/tiny-server.clj` if you're running the dev guide and would
  like to play with it in a REPL.

  ## Making an \"Easy\" Server

  The pre-built easy server component for Fulcro uses Stuart Sierra's Component library. The server has no
  global state except for a debugging atom that holds the entire system, and can therefore be easily restarted
  with a code refresh to avoid costly restarts of the JVM.

  You should have a firm understanding of [Stuart's component library](https://github.com/stuartsierra/component), since
  we won't be covering that in detail here.

  ### Constructing a base server

  The base server is trivial to create:

  ```
  (ns app.system
    (:require
      [fulcro.easy-server :as server]
      [app.api :as api]
      [fulcro.server :as prim]))

  (defn make-system []
    (server/make-fulcro-server
      ; where you want to store your override config file
      :config-path \"/usr/local/etc/app.edn\"

      ; The keyword names of any components you want auto-injected into the query/mutation processing parser env (e.g. databases)
      :parser-injections #{}

      ; Additional components you want added to the server
      :components {}))
  ```

  ### Configuring the server

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

  ### Pre-installed components

  When you start a Fulcro server it comes pre-supplied with injectable components that your
  own component can depend on, as well as inject into the server-side parsing environment.

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

  ### Processing Requests

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
  has your parser injections from server configuration.

  ## Modular Server

  Fulcro's easy server support is an attempt to get you going quickly, but it suffers from a few drawbacks:

  - Library authors cannot easily provide composable full-stack components.
  - The Ring stack can only be modified at pre-hook and post-hook locations.
  - Adding new features to easy-server would lead to a swiss-army knife API with a dizzying array of options.

  So when you outgrow the easy server Fulcro provides you with a more modular approach. This leads to
  more control, clearer extension points, and a simple API.

  It does, however, require a bit more work to get started.

  ### Overall Structure

  The modular server still uses components to make sure your code is reloadable. The primary difference is that the
  modular server makes no assumptions about how you want to build the web server itself. You could do anything from
  build a `ring-defaults` handler and augment it and drop the resulting processing pipeline into a Java Servlet. The options
  are endless.

  You must build three things:

  - A Module to handle API requests
  - Middleware to join the API processing with the rest of your request processing
  - Something to get you on the network (web server, servlet, etc.)

  The modules get composed into an API handler (which is found by injecting its well-known component), the API handler
  is injected into the middleware component (where you hook it into the correct place), and finally your middleware
  is injected into the network capable thing.

  The basic overall picture is shown below. Note the dotted arrows are meant to be injection, the solid lines are
  composition, and the dashed colored lines are the request/response flow.

  <img src=\"svg/modular-server.svg\">

  #### Step 1: A Module

  ```
  (defrecord APIModule []
    fulcro.server/Module
    (system-key [this] :api-module)          ; this module will be known in the component system as :api-module. Allows you to inject the module.
    (components [this] {})                   ; Additional components to build. This allows library modules to inject other dependencies that it needs into the system. Commonly empty.
    fulcro.server/APIHandler
    (api-read [this] core/server-read)       ; read/mutate handlers (as function) Here we're using the fulcro multimethods so defmutation et al will work.
    (api-mutate [this] core/server-mutate))
  ```

  The `fulcro.server/Module` protocol is required, and allows the component to appear in the module list.

  NOTE: Your module will be treated as a Stuart Sierra component, so you may also add the `Lifecycle` protocol and
  participate in the `start/stop` sequence if you need to.

  ##### The System Key

  The `(system-key)` protocol method must return a keyword. This keyword will become the dependency name that this module
  is known as in the Stuart Sierra component system. This allows modules to have inter-dependencies. For example, you
  could have a module that provides API parsing and one that provides utility support for image processing. Not only do you need such
  a component to end up in the processing chain, other components (like the web server itself) might need to access
  the component to obtain access to raw image data for image serving through traditional image URLs.

  If you're writing a module for a reusable library then it is suggested that you namespace this keyword to avoid collision.

  ##### The Components

  The `(components)` method must return a map that conforms to the Stuart Sierra component's definition of a dependency
  map. The keys of such a map indicate what keyword you'd like the dependency to be known **as**, and the value
  must be a component that you want created (or the keyword of a component that you know will exist elsewhere in the
  system on startup). This allows library modules to declare, create, or otherwise hook to other things in your system.

  Most basic applications can just return nil or an empty map.

  #### The APIHandler Protocol

  The APIHandler protocol is how you hook into the API requests. The methods of this protocol need to return
  a **function** due to the need for module composition by Fulcro.

  If you'd like to use the macros like `defquery-root`, then you should provide a module that returns
  `fulcro.server/server-read` and `fulcro.server/server-mutate` respectively. You may, however, return
  any function you please.

  ### Step 2 – The Middleware Stack

  When the Fulcro modular server starts it will compose all known modules into an internal component known under the
  key `:fulcro.server/api-handler`. This component contains the key `:middleware` which holds a Ring middleware function
  wrapping function that can handle Fulcro API requests. So, to build your middleware you simply inject that component
  into your own middleware component and create your Ring stack:

  ```
  ; We'll use a trick in a moment to inject the :fulcro.server/api-handler AS :api-handler here
  ; :full-server-middleware is where we'll store our resulting middleware stack for the web server's use
  (defrecord CustomMiddleware [full-server-middleware api-handler]
    component/Lifecycle
    (start [this]
      (let [wrap-api (:middleware api-handler)] ; pull the middleware function out
        (assoc this :full-server-middleware
                    (-> not-found
                      (wrap-resource \"public\")
                      wrap-api                            ; from fulcro-system modules. Handles /api
                      fulcro.server/wrap-transit-params   ; REQUIRED
                      fulcro.server/wrap-transit-response ; REQUIRED
                      wrap-content-type ; REQUIRED
                      wrap-not-modified ; these are recommended
                      wrap-params
                      wrap-gzip))))
    (stop [this] (dissoc this :full-server-middleware)))
  ```

  This component would be set up to have the API handler injected by wrapping its creation in `component/using` like
  so:

  ```
  (component/using
    (map->CustomMiddleware {})
    ; Inject (and remap) the generated api handler AS api-handler
    {:api-handler   :fulcro.server/api-handler })
  ```

  The rename makes it possible to refer to the simpler name within the `CustomMiddleware`. If you don't rename it, then
  instead of using a declared field of the `defrecord` you'd have to pull it out via `(:fulcro.server/api-handler this)`.

  ### Step 3 – The Web Server

  Now that you have Ring middleware, you can do whatever you want. You have a simple way to get a function that
  can handle any request just by injecting the above `CustomMiddleware` into it. Here's an example using HTTP Kit:

  ```
  ; server-middleware-component will be an injected instance of CustomMiddleware
  (defrecord WebServer [^CustomMiddleware server-middleware-component server port]
    component/Lifecycle
    (start [this]
      (try
        (let [server-opts    {:port 4000}
              port           (:port server-opts)
              ; pull out the middleware function from the component
              middleware     (:full-server-middleware server-middleware-component)
              ; install the middleware and start the server
              started-server (run-server middleware server-opts)]
          (timbre/info (str \"Web server (http://localhost:\" port \")\") \"started successfully. Config of http-kit options:\" server-opts)
          (assoc this :port port :server started-server))
        (catch Exception e
          (println \"Failed to start web server \" e)
          (throw e))))
    (stop [this]
      (if-not server this
                     (do (server)
                         (println \"web server stopped.\")
                         (assoc this :server nil)))))
  ```

  ### Putting it All Together

  The only thing that remains is to set up your overall system so that the components are all known by the names
  you've assumed for them.

  ```
  (defn make-system []
    (fulcro.server/fulcro-system
      {:components {:server-middleware-component (component/using
                                                   (map->CustomMiddleware {})
                                                   ; inject (and rename) the generated API handler
                                                   {:api-handler   :fulcro.server/api-handler })
                    ; The web server itself, which needs the config and full-stack middleware.
                    :web-server                  (component/using (map->WebServer {})
                                                   [:config :server-middleware-component])}
       ; The Modules (automatically injected as :fulcro.server/api-handler)
       :modules    [(map->APIModule {})]}))
  ```

  ### Module Composition

  Modules are composed for you when you supply them to the `fulcro-system`:

  ```
  (def your-server
    (fulcro.server/fulcro-system
      {:components { ... }
       :modules [(make-mod1) (make-mod2) ...]}))
  ```

  When an API request comes in, **each** query element (prop, join, mutation) will be sent (one at a time)
  into the API handler that appears first in this vector.
      - If the API handler returns nil, it will try the next and so on in a chain until one returns non-nil
      - If all return nil, then the remainder of the web processing will be sent the request

  ### Parsing Environment

  In the easy server you have explicity `:parser-injections`. In the module system, Fulcro will merge your module itself
  **into** the parsing `env` before calling your API handler functions. This means that anything you inject into the
  **module** will be visible in the `env`.

  ```
  (def your-server
    (fulcro.server/fulcro-system
      {:components { ... }
       :modules [(component/using (make-mod1) [:user-db])
                 (component/using (make-mod2) [:session-store]) ...]}))
  ```

  In the example above module 1 will see `:user-db` in `env`, and module 2 will see the `:session-store`.

  ## Application Configuration

  Since you'll still need to configure your web server, it might be useful to note that the configuration used
  by the easy server is a component you can inject into your modular server!

  ```
  (core/fulcro-system
    {:components {:config (fulcro.server/new-config config-path)
                  :server (component/using (make-server) [:config])}
     :modules [(make-mod1)]})
  ```

  It supports pulling in values from the system environment, overriding configs with a JVM option, and more.

  See the docstrings on `new-config` or the documentation on the easy server for more details.

  ### Full Example

  See the [Fulcro Template](https://github.com/fulcrologic/fulcro-template/blob/develop/src/main/fulcro_template/server.clj) for a complete example
  that also injects and uses additional components.

  ### Using Other Web Server Technologies

  Since the modular support gives you the ability to grab a function that can serve the API, you can use that to
  plug into whatever you want. For example, using Pedestal would just require placing your API ring stack into
  something like their [Ring example](https://github.com/pedestal/pedestal/tree/master/samples/ring-middleware).

  The primary thing to remember is that the transit stuff must happen on the incoming/outgoing data.
  ")
