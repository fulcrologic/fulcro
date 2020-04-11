(ns com.fulcrologic.fulcro.react.hooks
  (:require
    #?@(:cljs
        [[goog.object :as gobj]
         ["react" :as react]])))

#?(:cljs (set! js/React react))

(defn use-state
  "A simple wrapper around React/useState. Returns a cljs vector for easy destructuring.

  React docs: https://reactjs.org/docs/hooks-reference.html#usestate"
  [initial-value]
  (into-array (js/React.useState initial-value)))

(defn use-effect
  "A simple wrapper around React/useEffect.

  React docs: https://reactjs.org/docs/hooks-reference.html#useeffect"
  ([f]
   (js/React.useEffect f))
  ([f args]
   (js/React.useEffect f (to-array args))))

(defn use-context
  "A simple wrapper around React/useContext."
  [ctx]
  (js/React.useContext ctx))

(defn use-reducer
  "A simple wrapper around React/useReducer. Returns a cljs vector for easy destructuring

  React docs: https://reactjs.org/docs/hooks-reference.html#usecontext"
  ([reducer initial-arg]
   (into-array (js/React.useReducer reducer initial-arg)))
  ([reducer initial-arg init]
   (into-array (js/React.useReducer reducer initial-arg init))))

(defn use-callback
  "A simple wrapper around React/useCallback. Converts args to js array before send.

  React docs: https://reactjs.org/docs/hooks-reference.html#usecallback"
  ([cb]
   (js/React.useCallback cb))
  ([cb args]
   (js/React.useCallback cb (to-array args))))

(defn use-memo
  "A simple wrapper around React/useMemo. Converts args to js array before send.

  React docs: https://reactjs.org/docs/hooks-reference.html#usememo"
  ([cb]
   (js/React.useMemo cb))
  ([cb args]
   (js/React.useMemo cb (to-array args))))

(defn use-ref
  "A simple wrapper around React/useRef.

  React docs: https://reactjs.org/docs/hooks-reference.html#useref"
  ([] (js/React.useRef nil))
  ([value] (js/React.useRef value)))

(defn use-imperative-handle
  "A simple wrapper around React/useImperativeHandle.

  React docs: https://reactjs.org/docs/hooks-reference.html#useimperativehandle"
  [ref f]
  (js/React.useImperativeHandle ref f))

(defn use-layout-effect
  "A simple wrapper around React/useLayoutEffect.

  React docs: https://reactjs.org/docs/hooks-reference.html#uselayouteffect"
  ([f]
   (js/React.useLayoutEffect (wrap-effect f)))
  ([f args]
   (js/React.useLayoutEffect (wrap-effect f) (to-array args))))

(defn use-debug-value
  "A simple wrapper around React/useDebugValue.

  React docs: https://reactjs.org/docs/hooks-reference.html#uselayouteffect"
  ([value]
   (js/React.useDebugValue value))
  ([value formatter]
   (js/React.useDebugValue value formatter)))
