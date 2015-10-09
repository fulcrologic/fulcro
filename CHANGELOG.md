# 0.2.0

- Integrated test reporting with new smooth-spec support for manual testing
- Added test folding to UI (click on a namespace)
- Added test filtering to test rendering UI
- BREAKING CHANGE: Refactored all test suite/rendering into untangled.test.suite
    - PORTING NOTE:
```
(ns mytests (:require [untangled.test.suite :as t :include-macros true]))
(t/test-suite suite-name ...)
(suite-name)
```
- BREAKING CHANGE: Modified the rendering such that an external rendering loop is used. Added centralized rendering. 
This makes it possible to test React render updates on real DOM without triggering re-renders. (test-suite renderer is
based on state changes so that the two can co-exist).
    - PORTING NOTE:
```
(ns myapp.core
  (:require [untangled.core :as core]
            [untangled.application :as app]))
;; Add your app using untangled.application/add-application
;; figwheel note: use defonce so you don't re-create/re-add on a hot code reload
(defonce myapp (app/add-application (core/new-application #(MyApp %1 %2) (make-myapp))))
(app/start-rendering) ; Start the rendering loop for all added apps. It is safe to call multiple times. 
```
