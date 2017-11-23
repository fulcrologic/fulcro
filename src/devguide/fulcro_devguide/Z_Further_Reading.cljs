(ns fulcro-devguide.Z-Further-Reading
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(defcard-doc
  "
  # Further Reading

  ## TODO MVC

  Even though Fulcro is not MVC, we've provided a
  [full-stack implementation](https://github.com/fulcrologic/fulcro-todomvc) of this well-known
  project.

  ## The #fulcro Channel on Slack

  If you have questions, you can get help via [#fulcro channel](https://clojurians.slack.com/messages/fulcro)
  of the clojurians.slack.com team.

  ")
