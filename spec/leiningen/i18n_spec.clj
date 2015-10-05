(ns leiningen.i18n-spec
  (:require [clojure.test :refer (is deftest run-tests testing do-report)]
            [smooth-spec.core :refer (specification behavior provided assertions)]
            [leiningen.i18n-spec-fixtures :as fixture]

            [leiningen.i18n :as e]))




(specification "the configure-i18n-build function"
               (let [i18n-build (e/configure-i18n-build fixture/prod-build)]
                 (behavior "assigns :id \"i18n\" to the build"
                           (assertions
                             (:id i18n-build) => "i18n"))

                 (behavior "enables :optimizations :whitespace on the build"
                           (assertions
                             (get-in i18n-build [:compiler :optimizations]) => :whitespace))))



(specification
  "the lookup-modules function"
  (let [proj-with-modules {:cljsbuild {:builds [{:id       "production"
                                                 :compiler {:modules {}}}]}}

        proj-without-modules {:name           "survey"
                              :cljsbuild      {:builds [{:id       "production"
                                                         :compiler {:main       "survey.main"
                                                                    :output-dir "res/pub/js/comp/out"}}]}
                              :untangled-i18n {:translation-namespace 'survey.i18n
                                               :default-locale        "fc-KY"}}]

    (behavior
      "when build does not contain modules"
      (behavior
        "adds :optimizations :advanced to the :compiler"
        (assertions
          (-> (e/lookup-modules proj-without-modules '()) :compiler :optimizations) => :advanced))

      (behavior
        "removes the top-level :main"
        (assertions
          (contains? (:compiler (e/lookup-modules proj-without-modules '())) :main) => false))

      (behavior
        "returns a suggested :main module in the map"
        (assertions
          (->
            (e/lookup-modules
              proj-without-modules '()) :compiler :modules :main) => {:output-to "res/pub/js/comp/out/survey.js"
                                                                      :entries   #{"survey.main"}}))
      (behavior
        "returns locale modules in the map"
        (assertions
          (->
            (e/lookup-modules
              proj-without-modules '("en-US" "fc-KY")) :compiler :modules :fc-KY) => {:output-to "res/pub/js/comp/out/fc-KY.js"
                                                                                      :entries   #{"survey.i18n.fc-KY"}})))
    (behavior
      "when build contains modules"
      (behavior
        "returns nil"
        (assertions
          (e/lookup-modules proj-with-modules '()) => nil)))))


