(ns fulcro-devguide.H02-Making-An-Easy-Server
  (:require [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(defcard-doc
  "
  # Building a Server

  In order to work through the following sections you need to know how to put together and run a Fulcro server. It
  actually takes very little code to get one going: A couple of config files, and a couple of short clj files.

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

  ## Development Server Code

  When working with server code you'd like to be able to start and stop/reload/start the server to make changes to
  your code available. We typically put these together in the `user` namespace under a `dev` source folder. Something
  like this works:

  ```
  (ns user
    (:require
      [clojure.tools.namespace.repl :as tools-ns :refer [disable-reload! refresh clear set-refresh-dirs]]
      [my-server :as svr]
      [com.stuartsierra.component :as component]))

  ; Make sure the cljs output folder is NOT on the refresh dirs list, or you'll get screwy results
  (set-refresh-dirs \"src/main\" \"src/test\" \"src/dev\")

  ; An atom to hold onto the running server component
  (defonce server (atom nil))

  (defn stop \"Stop the running web server.\" []
    (when @server
      (swap! server component/stop)
      (reset! server nil)))

  (defn go \"Load the overall web server system and start it.\" []
    (reset! server (svr/make-system))
    (swap! server component/start))

  (defn restart
    \"Stop the web server, refresh all namespace source code from disk, then restart the web server.\"
    []
    (stop)
    (refresh :after 'user/run-demo-server))
  ```

  You should now be able to start a Clojure REPL, and within the user namespace run things like:

  ```
  user=> (go) ; start from stopped state
  user=> (restart) ; should reload code and restart web server
  user=> (tools-ns/refresh) ; IFF restart fails to start, followed by (go) again
  ```

  You'll want to get that working before you move on to trying out the code in the remaining sections of server interaction.

  ## What's Next?

  Now let's try to [load some stuff](#!/fulcro_devguide.H05_Server_Interactions_Basic_Loading).
  ")

