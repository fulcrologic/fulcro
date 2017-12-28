(ns fulcro-tutorial.Z-Deploying-To-Heroku
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

  ## Sample Project

  The [fulcro-template](http://github.com/fulcrologic/fulcro-template) is pre-configured for easy deployment to Heroku.
  ")
