(ns fulcro-css.css-protocols)

(defprotocol CSS
  (local-rules [this] "Specifies the component's local CSS rules")
  (include-children [this] "Specifies the components (typically direct children) whose CSS should be included."))

(defprotocol Global
  (global-rules [this] "DEPRECATED. Will be removed in a future release. Do not use for new applications. Use the `$` prefix instead."))

