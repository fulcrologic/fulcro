(ns untangled-devguide.I-Building-A-Server-Exercises
  (:require-macros [cljs.test :refer [is]]
                   [untangled-devguide.tutmacros :refer [untangled-app]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [untangled.client.core :as uc]
            [untangled.client.data-fetch :as df]
            [untangled.client.mutations :as m]
            [devcards.core :as dc :include-macros true :refer-macros [defcard defcard-doc dom-node]]
            [cljs.reader :as r]
            [om.next.impl.parser :as p]))

; TODO: Need more exercises.

(defcard-doc
  "# Server exercises

   These exercises require that you are using a cloned (local) version of the guide, since you'll need to write
   plain Clojure code for your server. The project file is already set up to support server development for use
   with the sample application in this file.

   ## Configuration

   The default configuration file is called `/usr/local/etc/app.edn`. You may change this by editing `system.clj`. You
   must create a configuration file at that location and it should contain the following:

   ```
   { :port 8080 }
   ```

   which will be the server web port.

   ## Getting it started

   If you're using IntelliJ, simply add a Clojure REPL (local). No additional configuration is necessary. Once the REPL
   has started you just run:

   ```
   (go)
   ```

   You should now be able to see te guide at [http://localhost:8080](http://localhost:8080). You must use
   the guide through the server in order for it to be able to communicate properly.

   ## Refreshing the code

   The server uses Stuart Sierra's component library, and clojure namespace tools. As a result, you can quickly restart
   the server (with a code refresh):

   ```
   (reset)
   ```

   If you have a compile error, this may fail. In this case you should be able to get going again with:

   ```
   (refresh)
   (start)
   ```

   Don't call `(refresh)` if the web server is running or you'll lose your system state and have to restart the REPL!

   ## Understanding the prebuilt server

   `system.clj` contains the main server definition. It is miminal, but includes a configuration path and an Om
   parser. The parser is configured with a read and mutate function.

   `main.clj` is just the entry point. Not much to see there.

   `api.clj` is the rest of the server. This is where you'll write your query and mutation code.

   ## Your first server query

   If you look in `api.clj` you'll see the read entry point. For simplicity we're using a case statement, but you
   could just as well use a multi-method or anything else.

   The UI card below is a complete Untangled client. Make sure your browser is running this page through
   your server port. If you press the button, you should see the app state database update:

   - The server has a built-in delay of 1s so you can see what is happening
   - You'll notice bookkeeping happening about the load
   - A state marker appears in the app state during the load
 ")

(defn render-something [n] (dom/div nil n))

(defui ^:once Ex1-Root
  static om/IQuery
  (query [this] [:ui/loading-data :something])
  Object
  (render [this]
    (let [{:keys [ui/loading-data something] :as props} (om/props this)]
      (dom/div nil
        (dom/h2 nil (str "Root " (if loading-data "(loading)" "")))
        (dom/button #js {:onClick (fn [evt] (df/load this :something nil))} "Load data")
        (df/lazily-loaded render-something something)))))

(defcard server-exercise-1
  "## Exercise 1 - Modify a Server Query

  First, play with this card and notice what happens. A global state marker (`:ui/loading-data`)
  is updated during loading, allowing you to place a global network indicator on your UI (you can
  query it anywhere via a link).

TODO: Make this better...this is so lame.
  A fetch state is placed in the application database at the top-level key given. The query for
  `load` should be a single item or collection (e.g. a join or a property).

  The data fetch render helper `lazily-loaded` can be used to render the various possible states of the
  data fetch (usually just loading, but failed and others are possible).

  Open the `api.clj` file, find the place where `:something` is in the case statement and change the
  return value (remember to leave the `{:value }` wrapper, which Om requires).

  Now `(reset)` your server and try the UI button again.
  "
  (untangled-app Ex1-Root)
  {:something 22}
  {:inspect-data true})
