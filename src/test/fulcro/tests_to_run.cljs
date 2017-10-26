(ns fulcro.tests-to-run
  (:require
    fulcro.client.impl.application-spec
    fulcro.client.impl.plumbing-spec
    fulcro.client.core-spec
    fulcro.client.data-fetch-spec
    fulcro.client.logging-spec
    fulcro.client.mutations-spec
    fulcro.client.network-spec
    fulcro.client.primitives-spec
    fulcro.client.routing-spec
    fulcro.client.util-spec
    fulcro.ui.forms-spec
    fulcro.server-render-spec
    fulcro.i18n-spec))

;********************************************************************************
; IMPORTANT:
; For cljs tests to work in CI, we want to ensure the namespaces for all tests are included/required. By placing them
; here (and depending on them in user.cljs for dev), we ensure that the all-tests namespace (used by CI) loads
; everything as well.
;********************************************************************************

