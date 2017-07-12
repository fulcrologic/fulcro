(ns fulcro-devguide.Z-Query-Quoting
  (:require-macros
    [cljs.test :refer [is]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.next.impl.parser :as p]
            [fulcro-devguide.queries.query-editing :as qe]
            [om.dom :as dom]
            [cljs.reader :as r]
            [fulcro-devguide.queries.query-demo :as qd]
            [devcards.util.edn-renderer :refer [html-edn]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]))

(defcard-doc
  "# Quoting queries

  In general you should do a full devguide on macros which will give you a great
  facility with quoting. This appendix to the devguide is to help you see the
  patterns that are commonly used in Fulcro/Om UI to properly encode
  queries on the UI.

  If you really want to see all of the edges, you might want to read
  [Quoting Without Confusion](https://blog.8thlight.com/colin-jones/2012/05/22/quoting-without-confusion.html)

  ## Plain quote

  Avoiding evalution of a form (the structural thing that immediately follows) is the purpose
  of `quote`. Nothing inside of a quoted form is evaluted (though you get it back as the
  data it represents). Therefore, if you want to use symbols or plain lists without fear you
  can to something like this:

  ```
  '[(f) _ ...]
  ```

  and you'll get back a vector containing a list (which contains a symbol), and two other
  symbols (`_` and `...`).

  Since Om queries use these things in the query notation, this is the most basic tool
  for getting the correct data  structure as a query.

  ## Syntax quotes

  Syntax quotes (which use &grave; ) are similar, but they allow unquoting (by prefixing a form to be
  evaluated with `~`) and also make sure all symbols are
  namespaced. So, if we are trying to compose queries we might want some nested bit
  of the expression to actually be evaluated:

  ```
  `[{:prop ~(om/get-query Child)}]    ===>    [{:prop [:id :child-thing]}]
  ```

  Note, however, that in this case the whole quoting thing is overkill! Nothing *needs* quoted,
  so the plain unquoted form would have evaluated to the same thing:

  ```
  [{:prop (om/get-query Child)}]      ===>    [{:prop [:id :child-thing]}]
  ```

  Some people choose to always use quoting so that later expansion of the query
  to include things like links ([:x _]) or recursion (`...`) do not cause problems. We
  typically choose to not use quoting unless it is necessary.

  ### Dealing with symbols in syntax quoting

  The automatic namespacing of symbols helps ensure that their use in macros is correct; however, it
  is at cross purposes to our Om queries.

  For example:

  ```
  `[(f)]
  ```

  will turn into:

  ```
  [(the.namespace.you.are.in/f)]
  ```

  which is particularly handy if you place your mutations into a namespace that you can alias in a require:

  ```
  (ns boo
     (:require [the-real-namespace-for-n :as n]))


  `[(n/f)]  ; becomes:  `[(the-real-namespace-for-n/f)]
  ```

  If you want to circumvent that kind of expansion, you can unquote-quote it:

  ```
  `[(~'f)]         ===>   [(f)]
  `[(~'n/f)]       ===>   [(n/f)]
  ```

  ")
