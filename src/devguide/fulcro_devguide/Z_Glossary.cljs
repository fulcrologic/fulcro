(ns fulcro-devguide.Z-Glossary
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

; TODO: add stuff here with links.
; TODO: Link existing terms back to docs

(defcard-doc
  "# Glossary of terms

  - `Default database format`: A tree of data where all of the objects that have an ident are replaced by that ident, and
    the actual data of those objects is moved to top-level tables. See `prim/tree->db`.
  - `Ident`: A unique identity, represented as a 2-tuple `vector` with a first element keyword. An ident need only
    be client unique, but will often be based on real server-persisted data. Examples might be `[:people/by-id 3]`
    and `[:ui.button/by-id 42]`. Fulcro can use these to find components that share state and should update together,
    and for other things like parse optimization.
  - `Join`: A join is a database term used when a value is used to refer to another. Joins can be to-one or to-many. In
  Fulcro, a to-one join will reference a map, and a to-many will refer to a vector. In both cases, a selector is supplied
  (e.g. `[{:join [:prop]}]`) and the selector (`[:join]`) will be applied to each of the items found.
  - `Stateless Component`: A component defined by `defui` that has no query. Such components must not change the structure
  of the incoming props (they must pass them down without removing structure).
  ")
