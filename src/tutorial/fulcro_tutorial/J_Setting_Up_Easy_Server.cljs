(ns fulcro-tutorial.J-Setting-Up-Easy-Server
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [devcards.util.edn-renderer :refer [html-edn]]
            [devcards.core :as dc :refer-macros [defcard-doc deftest]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [cljs.reader :as r]
            [fulcro.client.impl.parser :as p]
            [fulcro.client.mutations :as m]
            [fulcro.client :as fc]))

(defcard-doc
  "
  # Setting Up an Easy Server

  This section includes exercises that require an `easy` server. Use the solutions to the exercises of Putting It Together as
  a starting point. This section explains various ways to extend the `easy` server to deal with multiple
  configurations, add additional ring middleware, and handle extra routes.

  ## Working with Configuration

  The included server configuration component is rather easy to deal with, but you may have specific questions about
  how to use it in various circumstances, and from various perspectives.

  Normally you tell the server what your application's 'normal' configuration file is name (and where it lives) using
  the `:config-path` option of `make-fulcro-server` (as covered in the section on Building a Server). This helps
  ensure that you have a well-known location for configuration on each machine (dev, staging, production).

  The following sections talk about working with the configuration file content and location.

  ### Running with Alternate Configurations

  You can select any alternate EDN file on the filesystem (using an absolute path) or CLASSPATH (with a relative path)
  when you start the server. To do that, include a JVM option: `-Dconfig=path-to-file`.  So, for example, you could place a `staging.edn` file
  in `resources/config` in your project, and use that directly (even from the uberjar) with `-Dconfig=config/staging.edn`.

  #### Exercise 1

  Create a `staging.edn` in `resources/config` with this content:

  ```
  {:port 9001}
  ```

  and modify your startup to include the proper JVM parameter to use that config. Verify that when you restart your server
  it is running on port 9001.

  #### Exercise 2

  Place another configuration file somewhere on your disk (and choose some alternate port). Modify your JVM arguments to point to that
  configuration file and restart your REPL. Start the server and verify you have the correct config by either reading the logs
  (it should say what port it used) or hitting the port with your browser.

  ### Accessing Configuration Values in Components

  The configuration is loaded into a server component that can be injected into your own component. The `:config` component
  has a single entry: `:value`. The value at the `:value` key is the EDN from your config file.

  So basically, you write something like this:

  ```
  (ns app.my-component
    (:require
      [com.stuartsierra.component :as component]
      [taoensso.timbre :as timbre]))

  ; include config as something you expect to be in your component
  (defrecord SampleComponent [config]
    component/Lifecycle
    (start [this]
      ; assume the config is there, and is the EDN from the config file
      (let [my-config (-> config :value :your-key :your-sub-key)]
        ...)
      this)
    (stop [this] this))

  (defn make-sample-component []
    (component/using
      (map->SampleComponent {})
      [:config])) ; <-- declare you need config to be injected
  ```

  and add your component to the server:

  ```
  (core/make-fulcro-server
    ...
    :components {:sample (make-sample-component)}) ; <-- add your component to the server
  ```

  #### Exercise 3

  Create a custom component as shown above. Modify your configuration file to include:

  ```
  { ...
    :sample { :n 1 }}
  ```

  Your component should pull the value of `:n` from this file and log it (using `timbre/info`).

  Install the component in the source, `(reset)` your server, and verify you can see the config.

  ### Including Data from the Environment

  Configuration files can indicate that a value should come from the environment. There are two supported notations for
  this: `:env.edn/varname` and `:env/varname`. The former runs the environment variable through a Clojure reader, which
  means that something like this:

  ```
  $ export V='[1 2 3]'
  ```

  in the shell creates V with string content, but if you pull that into a config with:

  ```
  { :v :env.edn/V }
  ```

  then your configuration will contain a *real* clj vector!

  If you instead use:

  ```
  { :v :env/V }
  ```

  then your configuration will have the literal string.

  #### Exercise 4

  You can set the environment and JVM args in the IntelliJ Run Configuration under JVM args.
  If you're using the command line, of course you'll just set them in the environment and run with JVM options.
  Add the following JVM args and environment variables:

  JVM Args:
  ```
  -Dconfig=config/exercise4.edn
  ```

  Environment variables (e.g. in bash):
  ```
  export PORT=9090
  export MESSAGE=hello
  ```

  Running from the command line with lein:
  ```
  JVM_OPTS=-Dconfig=... lein run -m clojure.main
  ```

  You'll need to kill your server REPL and restart it. Create an `exercise4.edn` file in `resources/config` that
  sets the network port from the `PORT` environment variable (be careful, you want it to *not* be a string), and
  sets your sample component's `:n` from the value of the `MESSAGE` environment variable (which is a string).

  Start the REPL and start your server with `(go)` to see that it works as expected.

  ### Accessing Configuration Values in Queries and Mutations

  The configuration component can be injected into the env parameter of your queries and mutations just by adding
  it to the `:parser-injections`. Then all you
  have to do is pull it out and use it just like you did in your own component:

  ```
  (defn my-mutation [env k params]
     (let [config (-> env :config :value)]
        ...))
  ```

  #### Exercise 5

  Make sure `src/server/app/system.clj` injects the config into the parser.

  Then, in `src/server/app/api.clj` add a mutation (use `defmethod`) that logs your configuration value `:sample :n` using timbre from the
  `:action` of a new mutation named `(exercise5/trigger)`.

  The devcard below is a simple fulcro app that can trigger your mutation.

  ")

(defmethod m/mutate 'exercise5/trigger [e k p]
  ; TODO: Note how we're triggering the remote:
  {:remote true})

(defui Root
  static prim/InitialAppState
  (initial-state [this params] {})
  Object
  (render [this]
    (dom/button #js {:onClick #(prim/transact! this '[(exercise5/trigger)])} "Click Me")))

(defcard-fulcro server-trigger
  "This card will trigger your server mutation.

  **IMPORTANT:** You MUST be running this page from your server URL (port).

  When you click the button below it will trigger an `exercise5/trigger` remote mutation."
  Root)

(defcard-doc
  "
  Start your server, and *make sure you reload this tutorial on the port of your server* (e.g. replace the figwheel port 3449 on the app URL with
  your server port (see the server log for which port it is using)).

  You should see your config data logged when you trigger the mutation from the UI.

  *REMEMBER:* Server code does *not* hot reload. You have to manually `(reset)`.

  ## Request Processing

  If you're writing pure Fulcro code, all of the request processing is done for you; however, there are a number
  of cases where you might want to interact more with the request pipeline:

  - Look at headers/cookies
  - Add REST routes for other clients
  - Augment the Ring Middleware
  - Interfere with the request (e.g. enforce auth via headers before reaching endpoints)
  - Generate resources (like images)

  ### Adding REST-based Routes

  The most familiar method for working with other clients is to simply provide some REST routes. Fulcro server has
  integration with `bidi` so that these kinds routes can very quickly be added when creating the server. This mechanism
  is augmented with the environment (like in the parser), which means you can see parser injections, the request, etc.

  To configure extra routes, add an `:extra-routes` parameter to the server:

  ```
  (core/make-fulcro-server
    :extra-routes {:routes   [\"/path\" :route-key]
                   :handlers {:route-key (fn [env match] resp) }}
  ```

  The `:routes` use [https://github.com/juxt/bidi](bidi) match syntax, which ends with a keyword that indicates the
  route desired. The `handlers` map associates these route keys with a function that can handle the route. The handler
  is given the bidi `match`, which will include parsed parameters (see the bidi documentation for more info).

  #### Exercise 6

  Add the route `/sample` to your server that responds with the value of `:n` from the sample section of your config. Be
  sure to set the content type (see `ring.util.response`). Reset your server and test the route. Make sure the parser
  injections on the server inject `:config`!

  ### Augmenting the Ring Middleware Chain

  There are two points at which you can augment the Ring Middleware chain. Before the API and extra routes, or at the
  very end of the processing chain (e.g. to handle unhandled requests). The `handler` component of Fulcro server
  has protocol methods for doing so, which you must access from your own custom component.

  ```
  (defprotocol IHandler
    (set-pre-hook! [this pre-hook] \"sets the pre-hook handler\")
    (get-pre-hook [this] \"gets the current pre-hook handler\")
    (set-fallback-hook! [this fallback-hook] \"sets the fallback handler\")
    (get-fallback-hook [this] \"gets the current fallback handler\"))
  ```

  The basic pattern for hooking into the pre-hook or fallback-hook is to inject it into a startup component of your
  own making (which you'll add to your server), and use this protocol in your start:

  ```
  (defrecord HookComponent [handler]
    component/Lifecycle
    (start [this]
      (let [old-pre-hook (get-pre-hook handler)]
        (set-pre-hook! handler (comp extra-hook old-pre-hook additional-extra-hook)))
      this)
    (stop [this] this))

  (defn make-hook-component []
    (component/using
      (map->HookComponent {})
      [:handler]))
  ```

  So that you can add as much middleware on either side of the existing pre-hook (which starts out empty) as you care
  to. Using this pattern you can have any number of components that augment the middleware, though this is not recommended.

  #### Exercise 7

  Assume there is some way you can determine (e.g. a cookie) which user is sending a request, and you'd like to encode
  the user's information into the request. For simpliciy, use this simple Ring middleware wrapper:

  ```
  (defn wrap-user [handler]
    (fn [request]
      (handler (assoc request :user {:id \"Your Name\"}))))
  ```

  Add a component to your server that adds this to the pre-hook, then test it using your extra `/sample` route. Note that
  the request is in the `env` at `:request`, so that the added user is at `(-> env :request :user)`.

  ### Directly Using Ring Middleware on a Route

  In some cases you might want to create a reusable route handler that requires some middleware, but you don't want to
  risk cluttering the main Ring middleware stack with the extra wrapper. Remember that middleware is just composed functions,
  so instead of adding your wrapper to the hooks, use it directly in your handler.

  The general pattern for a BIDI route handler would therefore be:

  ```
  (defn handle-sample-with-ring-wrapper [env match]
    ((-> (fn [augmented-request] response-from-your-route) middleware1 middleware2 ...) (:request env)))
  ```

  #### Exercise 8

  Remove your component that injects `wrap-user` and instead compose wrap-user into your sample route handler directly.

  ## Cookies, Headers, and Login oh my!

  See the fulcro-template for examples of login. See `augment-response` on the server for modifying headers from the
  server. Use wrap-session to do sessions/cookies.

  Most of these techniques are demonstrated in the fulcro-template.

  ## Solutions to Exercises

  See `src/tutorial/solutions/advanced_server.clj` for sample solutions to all exercises.
  ")

