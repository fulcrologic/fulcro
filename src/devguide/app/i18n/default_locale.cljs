(ns app.i18n.default-locale (:require app.i18n.en [fulcro.i18n :as i18n]))

(reset! i18n/*current-locale* "en")

(swap! i18n/*loaded-translations* #(assoc % :en app.i18n.en/translations))