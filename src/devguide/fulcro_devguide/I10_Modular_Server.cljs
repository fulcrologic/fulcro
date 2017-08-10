(ns fulcro-devguide.I10-Modular-Server
  (:require [om.next :as om :refer [defui]]
            [om.dom :as dom]
            [fulcro.client.core :as fc]
            [fulcro.client.data-fetch :as df]
            [fulcro.client.mutations :as m]
            [devcards.core :as dc :include-macros true :refer-macros [defcard defcard-doc dom-node]]
            [cljs.reader :as r]
            [om.next.impl.parser :as p]))

(defcard-doc "
  # Modular Server

  Fulcro's easy server support is an attempt to get you going quickly, but it suffers from a few drawbacks:

  - Library authors cannot easily provide composable full-stack components.
  - The Ring stack can only be modified at pre-hook and post-hook locations.
  - Adding new features to easy-server would lead to a swiss-army knife API with a dizzying array of options.

  So when you outgrow the easy server Fulcro provides you with a more modular approach. This leads to
  more control, clearer extension points, and a simple API.

  It does, however, require a bit more work to get started.

  ## Overall Structure

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

  ### Step 1: A Module

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

  #### The System Key

  The `(system-key)` protocol method must return a keyword. This keyword will become the dependency name that this module
  is known as in the Stuart Sierra component system. This allows modules to have inter-dependencies. For example, you
  could have a module that provides API parsing and one that provides utility support for image processing. Not only do you need such
  a component to end up in the processing chain, other components (like the web server itself) might need to access
  the component to obtain access to raw image data for image serving through traditional image URLs.

  If you're writing a module for a reusable library then it is suggested that you namespace this keyword to avoid collision.

  #### The Components

  The `(components)` method must return a map that conforms to the Stuart Sierra component's definition of a dependency
  map. The keys of such a map indicate what keyword you'd like the dependency to be known **as**, and the value
  must be a component that you want created (or the keyword of a component that you know will exist elsewhere in the
  system on startup). This allows library modules to declare, create, or otherwise hook to other things in your system.

  Most basic applications can just return nil or an empty map.

  ### The APIHandler Protocol

  The APIHandler protocol is how you hook into the API requests. The methods of this protocol need to return
  a **function** due to the need for module composition by Fulcro.

  If you'd like to use the macros like `defquery-root`, then you should provide a module that returns
  `fulcro.server/server-read` and `fulcro.server/server-mutate` respectively. You may, however, return
  any function you please.

  ## Step 2 – The Middleware Stack

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

  ## Step 3 – The Web Server

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

  ## Putting it All Together

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

  ## Module Composition

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

  ## Parsing Environment

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

  ## Full Example

  See the [Fulcro Template](https://github.com/fulcrologic/fulcro-template/blob/develop/src/main/fulcro_template/server.clj) for a complete example
  that also injects and uses additional components.

  ## Using Other Web Server Technologies

  Since the modular support gives you the ability to grab a function that can serve the API, you can use that to
  plug into whatever you want. For example, using Pedestal would just require placing your API ring stack into
  something like their [Ring example](https://github.com/pedestal/pedestal/tree/master/samples/ring-middleware).

  The primary thing to remember is that the transit stuff must happen on the incoming/outgoing data.
  ")

