(ns fulcro-devguide.C-App-Database-Exercises
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc deftest]]
            [cljs.reader :as r]))

(def cars-table
  {
                                        ; TODO (exercise 1): Add a :cars/by-id table
   })

(def favorites
                                        ; TODO (exercise 2): merge your `cars-table` from above here
  {
                                        ; TODO (exercise 2): Add a :favorite-car key that points to the Nissan Leaf via an ident
   })

(def ex3-uidb
  {
                                        ; TODO (exercise 3): Add tables. See exercise text.
   })

(defcard-doc
  "
  # App database exercises

  There are TODO placholders in the \"tables\" in this file. Replace the TODO with code
  to make the tests pass.

  When you have completed the task described by an exercise, the tests
  for those exercises will turn green.")

(dc/deftest exercise-1
  "In this exercise, you'll verify that you understand how to create a
  database table in the default Om database format.

  Create a table named `:cars/by-id` with the following items:

  ```
  { :id 1 :make \"Nissan\" :model \"Leaf\" }
  { :id 2 :make \"Dodge\" :model \"Dart\" }
  { :id 3 :make \"Ford\" :model \"Mustang\" }
  ```

  These tests shown below will pass when the table is
  correctly formatted. Remember that we need to `get-in` the database
  to retrieve in ident's value. Think get in \"table\" at \"id\" or \"index\"
  to retrieve the required data. So make sure your database is responsive to this
  lookup pattern.
  "
  (is (= "Nissan" (-> cars-table (get-in [:cars/by-id 1]) :make)))
  (is (= "Dodge" (-> cars-table (get-in [:cars/by-id 2]) :make)))
  (is (= "Ford" (-> cars-table (get-in [:cars/by-id 3]) :make))))

(dc/deftest exercise-2
  "In this exercise, you'll use Idents to link together data
  in an app database.

  Merge the cars table into provided favorites database, then
  add a `:favorite-car` key that uses an ident to reference
  the Nissan Leaf. Remember that `:favorite-car` should be
  point to a single instance.

  "
  (is (= "Nissan" (->> (get favorites :favorite-car) (get-in favorites) (:make)))))

(dc/deftest exercise-3
  "This exercise has you build up more of a UI, but all as normalized
  components.

  Say you want to have the following UI data:

  ```
  { :main-panel { :toolbar { :tools [ {:id 1 :label \"Cut\"} {:id 2 :label \"Copy\"} ]}
                  :canvas  { :data [ {:id 5 :x 1 :y 3} ]}}}
  ```

  but you want to normalize tool instances using :tools/by-id, data via :data/by-id.
  Also, you need to normalize the toolbar and the canvas data into their own tables respectively,
  and look them up with [:toolbar :main] and [:canvas :main]. Remember that `[:main-panel :toolbar]`
  and `[:main-panel :canvas]` refer to single instances like `:favorite-car`. While this pattern
  seems contrived in this example, you will find it provides flexibility in your UI.

  Build the normalized database in `ex3-uidb`.

  The following tests will pass when you get the format correct.
  "

  ;; It's worth noting that what follows the `=` is the query we use to access the database.
  ;; You'll find out more about queries in the next section.
  (is (= {:main-panel
          {:toolbar {:tools [{:label "Cut"} {:label "Copy"}]},
           :canvas  {:data [{:x 1, :y 3}]}}}
        (prim/db->tree '[{:main-panel [{:toolbar [{:tools [:label]}]}
                                     {:canvas [{:data [:x :y]}]}]}] ex3-uidb ex3-uidb))))

(defcard-doc
  "The solutions are in `src/guide/fulcro_devguide/app_database/solutions.cljs`. Now you're ready
  to see how to get the data out using [queries](#!/fulcro_devguide.D_Queries)")
