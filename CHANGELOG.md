# 0.2.0

- Integrated test reporting with new smooth-spec support for manual testing
- Added test folding to UI (click on a namespace)
- Added test filtering to test rendering UI
- Refactored all test suite/rendering into untangled.test.suite
    - PORTING NOTE:
```
(ns mytests (:require [untangled.test.suite :as t :include-macros true]))
(t/test-suite suite-name ...)
(suite-name)
```
