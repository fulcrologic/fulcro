(ns fulcro.tests-to-run
  (:require
    [clojure.spec.test.alpha :as st]
    fulcro.client.impl.application-spec
    fulcro.client.impl.data-targeting-spec
    fulcro.client.impl.parser-spec
    fulcro.client-spec
    fulcro.client.data-fetch-spec
    fulcro.logging-spec
    fulcro.client.mutations-spec
    fulcro.client.network-spec
    fulcro.client.primitives-spec
    fulcro.client.routing-spec
    fulcro.client.util-spec
    fulcro.client.logging-spec
    fulcro.history-spec
    fulcro.ui.forms-spec
    fulcro.ui.form-state-spec
    fulcro.server-render-spec
    fulcro.alpha.i18n-spec
    fulcro.client.alpha.dom-spec
    fulcro.client.alpha.css-keywords-spec
    fulcro.client.alpha.localized-dom-spec
    fulcro.client.css-spec
    fulcro.i18n-spec))

;********************************************************************************
; IMPORTANT:
; For cljs tests to work in CI, we want to ensure the namespaces for all tests are included/required. By placing them
; here (and depending on them in user.cljs for dev), we ensure that the all-tests namespace (used by CI) loads
; everything as well.
;********************************************************************************

;(st/instrument)
