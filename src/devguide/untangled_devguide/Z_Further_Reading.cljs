(ns untangled-devguide.Z-Further-Reading
  (:require-macros [cljs.test :refer [is]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(defcard-doc
  "
  # Further Reading

  ## Untangled Datomic

  The Untangled Datomic library has a few utilities that are useful if you've chosen Datomic as your
  back-end. The most useful is the support for schema migrations with Untangled Server. For more
  info see the README in
 [Untangled Datomic](https://github.com/untangled-web/untangled-datomic)

  ## Cookbook

  The [Untangled Cookbook](https://github.com/untangled-web/untangled-cookbook)
  is a great place to go to see examples of how to solve real-world use-cases in
  Untangled.

  ## TODO MVC

  Even though Om Next and Untangled are not MVC, we've provided a
  [full-stack implementation](https://github.com/untangled-web/untangled-todomvc) of this well-known
  project.

  ## The #untangled Channel on Slack

  If you have questions, you can get help via [#untangled channel](https://clojurians.slack.com/messages/untangled)
  of the clojurians.slack.com team.

  ")
