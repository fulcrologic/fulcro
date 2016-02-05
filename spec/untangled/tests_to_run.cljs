(ns untangled.tests-to-run
  (:require
    untangled.services.local-storage-io-spec
    untangled.client.protocol-support-spec
    untangled.client.impl.application-spec
    untangled.client.cache-manager-spec
    untangled.client.data-fetch-spec
    untangled.client.mutations-spec
    untangled.client.impl.om-plumbing-spec
    untangled.client.logging-spec
    untangled.client.impl.xhrio-spec
    untangled.i18n-spec))

;********************************************************************************
; IMPORTANT:
; For cljs tests to work in CI, we want to ensure the namespaces for all tests are included/required. By placing them
; here (and depending on them in user.cljs for dev), we ensure that the all-tests namespace (used by CI) loads
; everything as well.
;********************************************************************************


