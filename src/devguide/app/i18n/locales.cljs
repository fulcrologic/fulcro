(ns
 app.i18n.locales
 (:require
  goog.module
  goog.module.ModuleLoader
  [goog.module.ModuleManager :as module-manager]
  [fulcro.i18n :as i18n]
  app.i18n.en)
 (:import goog.module.ModuleManager))

(defonce manager (module-manager/getInstance))

(defonce
 modules
 #js
 {"en" "js/pages/app/i18n/en.js",
  "de" "js/pages/app/i18n/de.js",
  "es" "js/pages/app/i18n/es.js"})

(defonce module-info #js {"en" [], "de" [], "es" []})

(defonce
 ^:export
 loader
 (let
  [loader (goog.module.ModuleLoader.)]
  (.setLoader manager loader)
  (.setAllModuleInfo manager module-info)
  (.setModuleUris manager modules)
  loader))

(defn ^:export set-locale [l] (reset! i18n/*current-locale* l) (try (.execOnLoad manager l (fn after-locale-load [] (reset! i18n/*current-locale* l))) (catch js/Object e)))