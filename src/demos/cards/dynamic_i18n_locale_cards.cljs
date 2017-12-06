(ns cards.dynamic-i18n-locale-cards
  (:require [fulcro.client.dom :as dom]
            [fulcro.i18n :as i18n :refer [tr]]
            [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.client.mutations :as m]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.cards :refer [defcard-fulcro]]))

(defsc RootNode [this {:keys [ui/locale ui/react-key]}]
  {:query [:ui/locale :ui/react-key]}
  (dom/div nil
    (dom/h4 nil (tr "Locale Tests. Current locale: ") (name locale))
    (dom/p nil (tr "This is a test."))
    (mapv (fn [l] (dom/button #js {:key l :onClick #(prim/transact! this `[(m/change-locale {:lang ~l})])} l))
      ["en" "es-MX" "de"])))

(defcard-doc
  "# Dynamically Loaded Locales

  The i18n support allows for auto-generated locale code that can be configured into modules. This allows you to
  dynamically load locale-specific strings for your UI at runtime.

  The demos are configured for this, and the following steps were required:

  1. Code something that uses i18n (see this card's source below).
  2. Build the code with whitespace optimizations (see project.clj demo-i18n cljs build config). WITHOUT MODULES:
  `lein cljsbuild once demo-i18n`
  3. Use `fulcro.gettext/extract-strings` to extract the strings from the Javascript from (2):
     `(fulcro.gettext/extract-strings {:po \"resources/demos/i18n\" :js-path \"resources/public/js/demo-i18n.js\"})`
  4. Use a tool like PoEdit to make translation files (e.g. `es.po`) from the generated `messages.pot` file (see Dev Guide).
  5. Deploy the CLJC translation files to your source using: `(fulcro.gettext/deploy-translations {:src \"src/demos\" :po \"resources/demos/i18n\" :as-modules? true})`
  6. Add the languages as modules to the project's build (see project.clj `demos` build)

  The two build configs (at the time of this writing) were:

  ```
  {:id           \"demos\"
   :source-paths [\"src/main\" \"src/demos\"]
   :figwheel     {:devcards true}
   :compiler     {:devcards      true
                  :output-dir    \"resources/public/js/demos\"
                  :asset-path    \"js/demos\"
                  :preloads      [devtools.preload]
                  :modules       {:entry-point {:output-to \"resources/public/js/demos/demos.js\"
                                                :entries   #{cards.card-ui}}
                                  :de          {:output-to \"resources/public/js/demos/de.js\"
                                                :entries   #{translations.de}}
                                  :es-MX       {:output-to \"resources/public/js/demos/es-MX.js\"
                                                :entries   #{translations.es-MX}}
                                  :main        {:output-to \"resources/public/js/demos/main-ui.js\"
                                                :entries   #{cards.dynamic-ui-main}}}
                  :optimizations :none}}
  {:id           \"demo-i18n\"
   :source-paths [\"src/main\" \"src/demos\"]
   :compiler     {:devcards      true
                  :output-dir    \"resources/public/js/demo-i18n\"
                  :asset-path    \"js/demo-i18n\"
                  :output-to     \"resources/public/js/demo-i18n.js\"
                  :main          cards.card-ui
                  :optimizations :whitespace}}
  ```

  The code for the following card is simply:
  "
  (dc/mkdn-pprint-source RootNode))

(defcard-fulcro i18n-modules
  "This card dynamically loads the translations for the alternate locales. Watch the network panel of your developer tools in the browser as you click on them. Notice
  that they are only loaded the first time they are used."
  RootNode
  {}
  {:fulcro {:started-callback (fn [] (cljs.loader/set-loaded! :entry-point))}})
