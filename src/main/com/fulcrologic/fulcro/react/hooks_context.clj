(ns com.fulcrologic.fulcro.react.hooks-context
  "Dynamic vars for headless hooks context. This namespace is deliberately minimal
   to avoid cyclic dependencies - it only contains the context vars needed by both
   dom-server (for conditional component rendering) and hooks (for hook state management).")

(def ^:dynamic *current-path*
  "Vector path to the currently rendering component. E.g., [0 :key \"user-1\" 2]
   Set during headless rendering to enable hook context tracking.
   When non-nil, indicates we're in headless hooks mode."
  nil)
