(ns cards.A-Introduction
  (:require
    [devcards.core :as dc :include-macros true]
    [recipes.background-loads-client :as bg]
    [untangled.client.cards :refer [untangled-app]]
    [om.dom :as dom]))

(dc/defcard-doc
  "# Untangled Demos

  This devcards application includes a number of demonstrations of Untangled solving various problems. Some of the demos
  are client-side only, and some require a server.

  The source code is all in `src/demos`:

  ```
  src/demos
  |-- cards
  |   |-- ... the various card source
  |   |-- server.clj
  |   `-- server_api.clj
  `-- recipes
  |-- background_loads_client.cljs
  |-- background_loads_server.clj
  `-- ... the source for each demo in a client and (optional) server file
  ```

  ## Running the Demos

  In order to use the demos you must compile the devcards and start the demo server.

  ### Compiling the Demos

  You can compile the demos with:

  ```
  lein cljsbuild once demos
  ```

  If you'd like to edit the demos and see the changes live, then you can use figwheel:

  ```
  JVM_OPTS='-Ddemos' lein run -m clojure.main script/figwheel.clj
  ```

  NOTE: Figwheel is configured to run on port 8080. If you're looking at full-stack demos you'll need to run the
  demo server, and load the code from that port as discussed below.

  ### Running the Server

  You can do this from the command line with:

  ```
  lein run -m clojure.main
  ```

  and then running the server with:

  ```
  (run-demo-server)
  ```

  You should see some status messages from the server, including one that tells you what port it is running on. The
  preconfigured port is 8081, so you should now be able to see the demos at
  [http://localhost:8081/demos.html](http://localhost:8081/demos.html).


  ")

