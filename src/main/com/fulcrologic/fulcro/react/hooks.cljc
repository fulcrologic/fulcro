(ns com.fulcrologic.fulcro.react.hooks
  "Simple wrappers for React hooks support, along with additional predefined functions that do useful things
   with hooks in the context of Fulcro."
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; WARNING TO MAINTAINERS: DO NOT REFERENCE DOM IN HERE. This has to work with native.
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  #?(:cljs
     (:require
       [goog.object :as gobj]
       cljsjs.react)))

(defn use-state
  "A simple wrapper around React/useState. Returns a cljs vector for easy destructuring.

  React docs: https://reactjs.org/docs/hooks-reference.html#usestate"
  [initial-value]
  #?(:cljs (into-array (js/React.useState initial-value))))

(defn use-effect
  "A simple wrapper around React/useEffect.

  React docs: https://reactjs.org/docs/hooks-reference.html#useeffect"
  ([f]
   #?(:cljs (js/React.useEffect f)))
  ([f args]
   #?(:cljs (js/React.useEffect f (to-array args)))))

(defn use-context
  "A simple wrapper around React/useContext."
  [ctx]
  #?(:cljs (js/React.useContext ctx)))

(defn use-reducer
  "A simple wrapper around React/useReducer. Returns a cljs vector for easy destructuring

  React docs: https://reactjs.org/docs/hooks-reference.html#usecontext"
  ([reducer initial-arg]
   #?(:cljs (into-array (js/React.useReducer reducer initial-arg))))
  ([reducer initial-arg init]
   #?(:cljs (into-array (js/React.useReducer reducer initial-arg init)))))

(defn use-callback
  "A simple wrapper around React/useCallback. Converts args to js array before send.

  React docs: https://reactjs.org/docs/hooks-reference.html#usecallback"
  ([cb]
   #?(:cljs (js/React.useCallback cb)))
  ([cb args]
   #?(:cljs (js/React.useCallback cb (to-array args)))))

(defn use-memo
  "A simple wrapper around React/useMemo. Converts args to js array before send.

  React docs: https://reactjs.org/docs/hooks-reference.html#usememo"
  ([cb]
   #?(:cljs (js/React.useMemo cb)))
  ([cb args]
   #?(:cljs (js/React.useMemo cb (to-array args)))))

(defn use-ref
  "A simple wrapper around React/useRef.

  React docs: https://reactjs.org/docs/hooks-reference.html#useref"
  ([] #?(:cljs (js/React.useRef nil)))
  ([value] #?(:cljs (js/React.useRef value))))

(defn use-imperative-handle
  "A simple wrapper around React/useImperativeHandle.

  React docs: https://reactjs.org/docs/hooks-reference.html#useimperativehandle"
  [ref f]
  #?(:cljs (js/React.useImperativeHandle ref f)))

(defn use-layout-effect
  "A simple wrapper around React/useLayoutEffect.

  React docs: https://reactjs.org/docs/hooks-reference.html#uselayouteffect"
  ([f]
   #?(:cljs (js/React.useLayoutEffect f)))
  ([f args]
   #?(:cljs (js/React.useLayoutEffect f (to-array args)))))

(defn use-debug-value
  "A simple wrapper around React/useDebugValue.

  React docs: https://reactjs.org/docs/hooks-reference.html#uselayouteffect"
  ([value]
   #?(:cljs (js/React.useDebugValue value)))
  ([value formatter]
   #?(:cljs (js/React.useDebugValue value formatter))))
