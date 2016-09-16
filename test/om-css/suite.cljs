(ns om-css.suite
  (:require-macros
    [untangled-spec.reporters.suite :as ts])
  (:require
    untangled-spec.reporters.impl.suite
    ;; TODO: Require your specs here
    app.css-spec
    [devtools.core :as devtools]))

(enable-console-print!)

(devtools/enable-feature! :sanity-hints)
(devtools/install!)

(ts/deftest-all-suite app-specs #".*-spec")

(app-specs)

