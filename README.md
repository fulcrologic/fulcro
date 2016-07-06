# Untangled Client

The client library for the Untangled Web framework.

## Usage

The following instructions assume:

- You're using leiningen
- You'd like to be able to use Cursive REPL integration
- You'll use Chrome and would like to have nice support for looking at cljs data structures in the browser and
console log messages.

### Base Project file

In addition to the base untangled client library, the following are the minimum requirements for a project file:

- Choose a version of clj/cljs
- Choose a version of Om (Untangled requires Om, but treats it as a provided dependency)

If you copy/paste the following file into a `project.clj` it will serve as a good start:

```
(defproject sample "1.0.0"
  :description "My Project"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.8.51"]
                 [org.omcljs/om "1.0.0-alpha36"]
                 [navis/untangled-client "0.5.3"]]

  ; needed or compiled js files won't get cleaned
  :clean-targets ^{:protect false} ["resources/public/js/compiled" "target" "i18n/out"]

  ; needed for macros and our recommended figwheel setup
  :source-paths ["dev/server" "src/client"]

  :cljsbuild {:builds [{:id           "dev"
                        :source-paths ["dev/client" "src/client"]
                        :figwheel     true
                        :compiler     {:main                 "cljs.user"
                                       :asset-path           "js/compiled/dev"
                                       :output-to            "resources/public/js/compiled/app.js"
                                       :output-dir           "resources/public/js/compiled/dev"
                                       :recompile-dependents true
                                       :optimizations        :none}}]}

  ; figwheel dependency and chrome data structure formatting tools (formatting cljs in source debugging and logging)
  :profiles {:dev {:dependencies [[figwheel-sidecar "0.5.3-1"]
                                  [binaryage/devtools "0.6.1"]]}})
```

### Setting up Folders and Supporting files

Create the directories as follows (OSX/Linux):

```
mkdir -p src/client/app dev/client/cljs dev/server resources/public/css resources/public/js script
```

then create a base HTML file in `resources/public/index.html`:

```
<!DOCTYPE html>
<html lang="en">
    <head>
        <meta charset="utf-8">
        <title>Application</title>
        <link rel="stylesheet" href="css/app.css">
    </head>
    <body>
          <div id="app"></div>
        <script src="js/compiled/app.js"></script>
    </body>
</html>
```

and an empty CSS file:

```
touch resources/public/css/app.css
```

### Add base application source

Make the application itself, with an initial state, in `src/client/app/core.cljs`:

```
(ns app.core
  (:require [untangled.client.core :as uc]))

; The application itself, create, and store in an atom for a later DOM mount and dev mode debug analysis
; of the application.
; The initial state is the starting data for the entire UI
; see dev/client/user.cljs for the actual DOM mount
(defonce app (atom (uc/new-untangled-client :initial-state {:some-data 42})))
```

Notice that making the application is a single line of code.

then create the base UI in `src/client/app/ui.cljs`:

```
(ns app.ui
  (:require [om.next :as om :refer-macros [defui]]
            [untangled.client.mutations :as mut]
            [om.dom :as dom]))

;; A UI node, with a co-located query of app state.
(defui Root
  static om/IQuery
  (query [this] [:some-data])
  Object
  (render [this]
    (let [{:keys [some-data]} (om/props this)]
      (dom/div nil (str "Hello world: " some-data)))))
```


Create an application entry point for development mode in `dev/client/cljs/user.cljs`:

```
(ns cljs.user
  (:require
    [cljs.pprint :refer [pprint]]
    [devtools.core :as devtools]
    [untangled.client.logging :as log]
    [untangled.client.core :as uc]
    [app.ui :as ui]
    [app.core :as core]))

;; Enable browser console
(enable-console-print!)

;; Set overall browser loggin level
(log/set-level :debug)

;; Enable devtools in chrome for data structure formatting
(defonce cljs-build-tools
         (do (devtools/enable-feature! :sanity-hints)
             (devtools.core/install!)))

;; Mount the UI on the DOM
(reset! core/app (uc/mount @core/app ui/Root "app"))
```

technically, only the `ns` declaration and last line are necessary.

### Setting up Figwheel

We don't use the lein plugin for figwheel, as we'd rather have IntelliJ 
REPL integration, which we find works better with a figwheel sidecar
setup. 

The setup can read the cljs builds from the project file, and can also 
support specifying which builds you'd like to initially start via JVM 
options (e.g. -Dtest -Ddev will cause it to build the test and dev builds).

To get this, place the following in `dev/server/user.clj`:

```
(ns user
  (:require [figwheel-sidecar.repl-api :as ra]))

(def figwheel-config
  {:figwheel-options {:css-dirs ["resources/public/css"]}
   :build-ids        ["dev"]
   :all-builds       (figwheel-sidecar.repl/get-project-cljs-builds)})

(defn start-figwheel
  "Start Figwheel on the given builds, or defaults to build-ids in `figwheel-config`."
  ([]
   (let [props (System/getProperties)
         all-builds (->> figwheel-config :all-builds (mapv :id))]
     (start-figwheel (keys (select-keys props all-builds)))))
  ([build-ids]
   (let [default-build-ids (:build-ids figwheel-config)
         build-ids (if (empty? build-ids) default-build-ids build-ids)]
     (println "STARTING FIGWHEEL ON BUILDS: " build-ids)
     (ra/start-figwheel! (assoc figwheel-config :build-ids build-ids))
     (ra/cljs-repl))))
```

and you'll also want the following startup script in `script/figwheel.clj`:

```
(require '[user :refer [start-figwheel]])

(start-figwheel)
```

and now you can either start figwheel from the command prompt with:

```
lein run -m clojure.main script/figwheel.clj
```

or from Cursive in IntelliJ with a run profile:

- Local REPL
- Use clojure main in a normal JVM, not an NREPL
- Under Parameters, add: script/figwheel.clj

Once you've started figwheel you should be able to browse to:

```
http://localhost:3449
```

and see the UI.

## Next Steps

We recommend going through the [Untangled Tutorial](https://github.com/untangled-web/untangled-tutorial), 
which you should clone and work through on your local machine.

## A More Complete Project

We recommend cloning an existing full-stack application for real development.

The [Untangled TodoMVC project](https://github.com/untangled-web/untangled-todomvc) 
is set up as above, but includes additional setup:

- A more interesting, working, API
- Web Server
- Datomic Integration
- Testing (client and server) with Untangled Spec
- Running cljs tests in browsers via figwheel
- CI testing from command line
- More cljs builds, including a production one


