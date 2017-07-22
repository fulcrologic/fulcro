(ns fulcro-devguide.Z-Further-Reading
  (:require-macros [cljs.test :refer [is]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(defcard-doc
  "
  # Further Reading

  ## TODO MVC

  Even though Om Next and Fulcro are not MVC, we've provided a
  [full-stack implementation](https://github.com/fulcrologic/fulcro-todomvc) of this well-known
  project.

  ## The #fulcro Channel on Slack

  If you have questions, you can get help via [#fulcro channel](https://clojurians.slack.com/messages/fulcro)
  of the clojurians.slack.com team.

  ")
