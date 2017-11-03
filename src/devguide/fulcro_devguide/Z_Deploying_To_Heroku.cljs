(ns fulcro-devguide.Z-Deploying-To-Heroku
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(defcard-doc
  "
  # Deploying to Heroku

  There are a few things you need to do to make sure your app will work properly on Heroku:

  - Set up server to pull the web port from the PORT environment variable
  - Make sure `:min-lein-version` is in your project file
  - Include a Procfile
  - Use the clojure build pack (should be auto-detected)

  ## Setting PORT

  Fulcro server's PORT can be configured from the environment as follows:

  ```
  { :port :env.edn/PORT }
  ```

  place that in a file on your classpath, such as `src/config/prod.edn` (if `src` is your base source code directory). You
  could also use `resources` (which is technically more correct).

  ## Minimum lein version

  Heroku (at the time of this writing) defaults to lein version 1.7.x. This is almost certainly *not* what you want.

  ## Include a Profile

  If you're building an uberjar from one of our template projects, using Fulcro Server, and you've set up a config
  file as described above, then the following Procfile should work for you:

  ```
  web: java $JVM_OPTS -Dconfig=config/prod.edn -jar target/uberjar-name.jar\n
  ```

  ## Use a Clojure Build Pack

  Just follow the directions when adding a project. We've only used the git deployment method. Make sure you don't have
  a generated `pom.xml` as well as a `project.clj` file, or your project might get built using Maven instead of Lein.

  ## Sample Project File

  For reference, here are the non-dev parts of a project file that was working at the time of this writing:

  ```
  (defproject rad \"0.1.0-SNAPSHOT\"
              :description \"RAD Project\"
              :min-lein-version \"2.6.1\"  ;; VERY IMPORTANT!

              :dependencies [[org.clojure/clojure \"1.8.0\"]
                             [org.clojure/clojurescript \"1.9.229\"]
                             [commons-io \"2.5\"]

                             [fulcrologic/fulcro-client \"0.5.6\"]
                             [fulcro/om-css \"1.0.0\"]
                             [org.omcljs/om \"1.0.0-alpha45\"]

                             [liberator \"0.14.1\"]
                             [compojure \"1.5.1\"]

                             [cljsjs/victory \"0.9.0-0\"]
                             [fulcrologic/fulcro-spec \"0.3.9\" :scope \"test\"]
                             [org.clojure/core.async \"0.2.391\"]
                             [http-kit \"2.2.0\"]
                             [com.taoensso/timbre \"4.3.1\"]
                             [fulcrologic/fulcro-server \"0.6.2\" :exclusions [hiccup]]]

              :plugins [[lein-cljsbuild \"1.1.4\"]]

              :uberjar-name \"rad.jar\"

              :source-paths [\"src/server\"]

              :profiles {:uberjar {:main       rad.core
                                   :aot        :all
                                   :prep-tasks [\"compile\"
                                                [\"cljsbuild\" \"once\" \"production\"]]
                                   :cljsbuild  {:builds [{:id           \"production\"
                                                          :source-paths [\"src/client\"]
                                                          :jar          true
                                                          :compiler     {:main          rad.main
                                                                         :output-to     \"resources/public/js/rad.min.js\"
                                                                         :output-dir    \"resources/public/js/prod\"
                                                                         :asset-path    \"js/prod\"
                                                                         :optimizations :simple}}]}}})
  ```

  with the following source layout:

  ```
  |-- resources
  |   +-- public
  |       |-- css
  |       +-- js
  +-- src
      |-- client
      |   +-- rad
      |       |-- state
      |       +-- ui
      +-- server
          |-- config
          +-- rad
              |-- api
  ```
  ")
