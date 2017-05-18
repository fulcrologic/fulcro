(ns untangled-devguide.app-database.solutions)

(def cars-table
  {:cars/by-id {
                1 {:id 1 :make "Nissan" :model "Leaf"}
                2 {:id 2 :make "Dodge" :model "Dart"}
                3 {:id 3 :make "Ford" :model "Mustang"}}})

(def favorites (merge cars-table {:favorite-car [:cars/by-id 1]}))

(def ex3-uidb
  {
   :tools/by-id {1 {:id 1 :label "Cut"}
                 2 {:id 2 :label "Copy"}}
   :data/by-id  {1 {:id 5 :x 1 :y 3}}
   :toolbar     {:main {:tools [[:tools/by-id 1] [:tools/by-id 2]]}}
   :canvas      {:main {:data [[:data/by-id 1]]}}
   :main-panel  {:toolbar [:toolbar :main]
                 :canvas  [:canvas :main]}})
