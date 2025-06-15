(ns com.fulcrologic.fulcro.rendering.keyframe-render2
  "Just like keyframe render, but supports `:only-refresh` option."
  (:require
    [com.fulcrologic.fulcro.rendering.ident-optimized-render :as ior]
    [com.fulcrologic.fulcro.rendering.keyframe-render :as kr]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

(defn render-stale-components!
  "This function tracks the state of the app at the time of prior render in the app's runtime-atom. It
   uses that to do a comparison of old vs. current application state (bounded by the needs of on-screen components).
   When it finds data that has changed it renders all of the components that depend on that data."
  [app options]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} app
        {:com.fulcrologic.fulcro.application/keys [only-refresh]} @runtime-atom
        limited-refresh? (seq only-refresh)]
    (if limited-refresh?
      (let [{limited-idents true} (group-by eql/ident? only-refresh)]
        (doseq [i limited-idents]
          (ior/render-components-with-ident! app i)))
      (kr/render! app options))))

(defn render!
  "The top-level call for using this optimized render in your application.

  If `:force-root? true` is passed in options, then it forces a `keyframe` root render with
  that same option.

  This renderer always does a keyframe render *unless* an `:only-refresh` option is passed to the stack
  (usually as an option on `(transact! this [(f)] {:only-refresh [...idents...]})`. In that case the renderer
  will ignore *all* data diffing and will target refresh only to the on-screen components that have the listed
  ident(s). This allows you to get component-local state refresh rates on transactions that are responding to
  events that should really only affect a known set of components (like the input field).

  This option does *not* currently support using query keywords in the refresh set. Only idents."
  ([app]
   (render! app {}))
  ([app {:keys [force-root? root-props-changed?] :as options}]
   (if (or force-root? root-props-changed?)
     (kr/render! app options)
     (try
       (render-stale-components! app options)
       (catch #?(:clj Exception :cljs :default) e
         (log/info "Optimized render failed. Falling back to root render.")
         (kr/render! app options))))))

