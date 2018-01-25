(ns book.bootstrap.progress
  (:require [fulcro.client.primitives :refer [defsc]]
            [devcards.util.edn-renderer :refer [html-edn]]
            [book.bootstrap.helpers :as helper :refer [render-example sample]]
            [fulcro.ui.bootstrap3 :as b]
            [fulcro.client.mutations :as m]
            [fulcro.client.primitives :as prim]))

(defsc progress-bars
  [this {:keys [ui/pct]}]
  {:query         [:ui/pct]
   :ident         (fn [] [:progress :example])
   :initial-state {:ui/pct 40}}
  (render-example "100%" "180px"
    (b/button {:onClick (fn [] (m/set-value! this :ui/pct (- pct 10)))} "Less")
    (b/button {:onClick (fn [] (m/set-value! this :ui/pct (+ 10 pct)))} "More")
    (b/progress-bar {:kind :success :current pct})
    (b/progress-bar {:kind :success :animated? (< pct 100) :current pct})))

(def ui-progress (prim/factory progress-bars))

(defsc Root [this {:keys [example]}]
  {:initial-state {:example {}}
   :query [{:example (prim/get-query progress-bars)}]}
  (ui-progress example))
