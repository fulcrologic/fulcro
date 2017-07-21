(ns fulcro-devguide.I10-Modular-Server
  (:require [om.next :as om :refer [defui]]
            [om.dom :as dom]
            [fulcro.client.core :as fc]
            [fulcro.client.data-fetch :as df]
            [fulcro.client.mutations :as m]
            [devcards.core :as dc :include-macros true :refer-macros [defcard defcard-doc dom-node]]
            [cljs.reader :as r]
            [om.next.impl.parser :as p]))

["
  # Modular Server

  Fulcro's easy server support is an attempt to get you going quickly, but it suffers from a few drawbacks:

  - Library authors cannot easily provide composable full-stack components that interact with your parser.
  - The Ring stack can only be modified at pre-hook and post-hook locations.
  - Adding new features to easy-server would lead to a swiss-army knife API with a dizzying array of options.

  So, instead of continuing down that path Fulcro provides you with a more modular approach. This leads to
  more control, clearer extension points, and a simple API.

  It does, however, require a bit more work to get started.

  ## Overall Structure

  The modular server still uses components to make sure your code is reloadable. The primary difference is that the
  modular server makes no assumptions about how you want to build the web server itself. You could do anything from
  build a `ring-defaults` handler and augment it to drop the processing pipeline into a Java Servlet. The options
  are endless.

  The primary thing you will need to build from Fulcro is the API request handler middleware for query and mutation
  processing. This bit of code contains the logic required to speak correctly using Fulcro's transit-based network protocol.
  You must build all of the other bits of your server, but you should do so in the context of `fulcro.server/fulcro-system`
  so that all of the Stuart Sierra components will be joined together as a single thing that can be started and stopped
  for code refresh.

  The basic structure looks like this:

  ```
  (def your-server
    (fulcro.system/fulcro-system
      {:components { :database (make-db) ; A map of your s.s. components
                     :web-server (my-make-web-server)
                     :auth (my-auth-component) }
       :modules [(make-your-api-module) ]}))  ; composable modules (covered below)
  ```

  This function returns a Stuart Sierra component `system-map` (which you can pass to that library's start/stop).

  You must create and add the following things to your system:

  1. At least one module that can process Fulcro API requests.
  2. A request processing stack that composes the built-in API handler into your web server's processing chain
  3. A web server

  Basically, you build everything except the **composition** of Fulcro Modules.

  ## Building a Modular API Handler

  Modules participate both in API composition **and** component injection and start/stop (if you implement Lifecycle).

  ```
  (defrecord YourApiModule [database ...] ; the things you injected
    fs/Module ; participation in the component's dependency graph
    (system-key [this] :my-module) ; This will become the key your module is known as in dependency injection
    (components [this] {:database :database.modules/the-database}) ; A MAP (only) of the things you need injected
    fs/APIHandler ; Implement if your module needs to process API requests directly
    (api-read [this] ; must return a FUNCTION that can process a query element
      (fn [{:as env :keys [db]} k params] #_...))
    (api-mutate [this] ; must return a FUNCTION the can process a mutation
      (fn [{:as env :keys [db]} k params] #_...))

  (defn make-your-api-module [] (->YourApiModule))
  ```

  There's a lot of power here, so you should read the descriptions below carefully.

  ### The Module Protocol

  The `fulcro.server/Module` protocol is required, and allows the component to appear in the module list.
  You must implement two methods:

  #### The System Key

  The `(system-key)` protocol method must return a keyword. This keyword will become the dependency name that this module
  is known as in the Stuart Sierra component system. This allows modules to have inter-dependencies. For example, you
  could have a module that provides API parsing and utility support for image processing. Not only do you need such
  a component to end up in the processing chain, other components (like the web server itself) might need to access
  the component to obtain access to things like raw image data for image serving through traditional image URLs.

  The web server would simply include the image module's key as something it needs injected.

  If you're writing a library, it is suggested that you namespace this keyword to avoid collision.

  #### The Components

  The `(components)` method must return a map that conforms to the Stuart Sierra component's definition of a dependency
  map. The keys of such a map indicate what keyword you'd like the dependency to be injected **as**, and the value
  is the keyword of the component as it's known elsewhere in the system. It is possible for these two to be the
  same, in which case you'd just supply the same thing as key and value.

  NOTE: Anything you inject as a dependency will appear in the parsing `env` automatically!

  ### The APIHandler Protocol

  The APIHandler protocol is how you hook into the API requests. The methods of this protocol need to return
  a **functions** due to the need for later module composition by Fulcro.

  If you'd like to use the macros like `defquery-root`, then you should provide a module that returns
  `fulcro.server/server-read` and `fulcro.server/server-mutate` respectively. You may, however, return
  any function you please. This component is not pre-defined for you, because you almost certainly need to
  specify some dependency injections.

  ## Module Composition and Parsing `env`

  Modules are composed for you when you supply them to the `fulcro-system`:

  ```
  (def your-server
    (fulcro.server/fulcro-system
      {:components { ... }
       :modules [(make-mod1) (make-mod2) ...]}))
  ```

  1. When an API request comes in, **each** query element (prop, join, mutation) will be sent (one at a time)
  into the API handler that appears first in this vector.
      - If the API handler returns nil, it will try the next and so on in a chain until one returns non-nil
      - If all return nil, then the remainder of the web processing will be sent the request
  2. The module's injections are visible in the parsing `env`. In other words: If a module injected the
     database at `:db`, then `:db` will be available in `env`. This is part of the internal composition.
     Basically, Fulcro will merge your module (which can act as a map) with `env` and call your read or mutate
     with that updated env.

  ## A Complete Example


  ## Request Processing

  If you use the `fulcro-parser`, then your actual requestion processing is as described in Easy Server. If
  you want to know more about advanced processing, please read Advanced Server Query Processing."]
