(ns util.migration-fixtures.template)

;; Should NOT be loaded by library...meant to be a template file
(defn transactions [] [[{:bad :data}]])
