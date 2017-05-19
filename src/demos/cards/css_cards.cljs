(ns cards.css-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [recipes.css-client :as client]
    [untangled.client.cards :refer [untangled-app]]
    [om.dom :as dom]
    [untangled.client.css :as css]
    [garden.core :as g]))

(dc/defcard-doc
  "# Embedded CSS

   The Untangled CSS feature allows you to co-locate CSS on your components, and dynamically emit it into the DOM. This
   eliminates the need for *any* css in a disk file, or tooling. The CSS generation gets included (and minimized) in
   your generated js output!

  The source of the component in the demo looks like this:"
  (dc/mkdn-pprint-source client/MyCss)
  (dc/mkdn-pprint-source client/Child)
  (dc/mkdn-pprint-source client/Root)
  "and the generated CSS looks like this:"
  (str
    "```\n"
    (g/css (css/css client/Root))
    "\n```\n"))

(dc/defcard css-card
  "# Embedded CSS

  The label below should be red. Inspecting the element of the label will show you that the embedded CSS is there (it is
  embedded in the app's div, just above the label as a `<style>` element), and
  that the label has the correctly-namespaced class.

  "
  (untangled-app client/Root))
