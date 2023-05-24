(ns com.fulcrologic.fulcro.cards.dynamic-recursion-cards

  (:require
    [cljs.core.async :as async]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.networking.mock-server-remote :refer [mock-http-server]]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [nubank.workspaces.card-types.fulcro3 :as ct.fulcro]
    [nubank.workspaces.core :as ws]))

(defonce pretend-server-database
  (atom
    {:recipe/id           {1 {:recipe/id         1
                              :recipe/name       "Sandwich"
                              :recipe/line-items [{:recipe-line-item/id 1}
                                                  {:recipe-line-item/id 2}]}
                           2 {:recipe/id         2
                              :recipe/name       "Bread"
                              :recipe/line-items [{:recipe-line-item/id 4}
                                                  {:recipe-line-item/id 5}
                                                  {:recipe-line-item/id 6}]}
                           3 {:recipe/id         3
                              :recipe/name       "Peanut Butter"
                              :recipe/line-items [{:recipe-line-item/id 7}
                                                  {:recipe-line-item/id 8}]}}
     :ingredient/id       {1 {:ingredient/id   1
                              :ingredient/name "Peanuts"}
                           2 {:ingredient/id   2
                              :ingredient/name "Flour"}
                           3 {:ingredient/id   3
                              :ingredient/name "Water"}
                           4 {:ingredient/id   4
                              :ingredient/name "Salt"}
                           5 {:ingredient/id   5
                              :ingredient/name "Yeast"}}
     :recipe-line-item/id {1 {:recipe-line-item/id     1
                              :recipe-line-item/qty    2
                              :recipe-line-item/uom    :slice
                              :recipe-line-item/entity {:recipe/id 2}}
                           2 {:recipe-line-item/id     2
                              :recipe-line-item/qty    2
                              :recipe-line-item/uom    :tbsp
                              :recipe-line-item/entity {:recipe/id 3}}
                           3 {:recipe-line-item/id     3
                              :recipe-line-item/qty    2
                              :recipe-line-item/uom    :cup
                              :recipe-line-item/entity {:ingredient/id 2}}
                           4 {:recipe-line-item/id     4
                              :recipe-line-item/qty    1
                              :recipe-line-item/uom    :cup
                              :recipe-line-item/entity {:ingredient/id 3}}
                           5 {:recipe-line-item/id     5
                              :recipe-line-item/qty    1
                              :recipe-line-item/uom    :tsp
                              :recipe-line-item/entity {:ingredient/id 4}}
                           6 {:recipe-line-item/id     6
                              :recipe-line-item/qty    2
                              :recipe-line-item/uom    :tbsp
                              :recipe-line-item/entity {:ingredient/id 5}}
                           7 {:recipe-line-item/id     7
                              :recipe-line-item/qty    1
                              :recipe-line-item/uom    :lb
                              :recipe-line-item/entity {:ingredient/id 1}}
                           8 {:recipe-line-item/id     8
                              :recipe-line-item/qty    1
                              :recipe-line-item/uom    :tsp
                              :recipe-line-item/entity {:ingredient/id 4}}}}))

(pc/defresolver recipe-resolver [_ {:recipe/keys [id]}]
  {::pc/input  #{:recipe/id}
   ::pc/output [:recipe/name {:recipe/line-items [:recipe-line-item/id]}]}
  (get-in @pretend-server-database [:recipe/id id]))

(pc/defresolver recipe-line-item-resolver [_ {:recipe-line-item/keys [id]}]
  {::pc/input  #{:recipe-line-item/id}
   ::pc/output [:recipe-line-item/qty
                :recipe-line-item/uom
                {:recipe-line-item/entity [:recipe/id :ingredient/id]}]}
  (get-in @pretend-server-database [:recipe-line-item/id id]))

(pc/defresolver ingredient-resolver [_ {:ingredient/keys [id]}]
  {::pc/input  #{:ingredient/id}
   ::pc/output [:ingredient/name]}
  (get-in @pretend-server-database [:ingredient/id id]))

(pc/defresolver all-recipe-resolver [_ _]
  {::pc/output [{:recipe/all [:recipe/id]}]}
  (let [ids (keys (get @pretend-server-database :recipe/id))]
    {:recipe/all (mapv (fn [id] {:recipe/id id}) ids)}))

(def resolvers [recipe-resolver recipe-line-item-resolver ingredient-resolver all-recipe-resolver])

(def pathom-parser (p/parser {::p/env     {::p/reader                 [p/map-reader
                                                                       pc/reader2
                                                                       pc/open-ident-reader]
                                           ::pc/mutation-join-globals [:tempids]}
                              ::p/mutate  pc/mutate
                              ::p/plugins [(pc/connect-plugin {::pc/register [resolvers]})
                                           (p/post-process-parser-plugin p/elide-not-found)
                                           p/error-handler-plugin]}))



(declare Recipe ui-recipe)

(defsc Ingredient [this {:ingredient/keys [name]}]
  {:ident :ingredient/id
   :query [:ingredient/id
           :ingredient/name]}
  (dom/div (str name)))

(def ui-ingredient (comp/factory Ingredient {:keyfn :ingredient/id}))

(defsc DynamicRecipe [this {:recipe/keys [id]}]
  {:use-hooks? true}
  (let [recipe (hooks/use-component (comp/any->app this) Recipe {:initialize?    true
                                                                 :initial-params {:recipe/id id}
                                                                 :keep-existing? true})]
    ;; Load could be hooked into "expand" mutation to remove side-effect from UI logic
    (hooks/use-lifecycle (fn [] (df/load! this [:recipe/id id] Recipe)))
    (when recipe
      (ui-recipe recipe))))

(def ui-dynamic-recipe (comp/factory DynamicRecipe))

(defsc RecipeReference [this {:ui/keys     [expand?]
                              :recipe/keys [id name]}]
  {:ident :recipe/id
   :query [:ui/expand?
           :recipe/id
           :recipe/name]}
  (if expand?
    (ui-dynamic-recipe {:recipe/id id})
    (dom/div {:onClick (fn [] (m/toggle! this :ui/expand?))} name)))

(def ui-recipe-reference (comp/factory RecipeReference {:keyfn :recipe/id}))

(defn union-ident [props]
  (if (:recipe/id props)
    [:recipe/id (:recipe/id props)]
    [:ingredient/id (:ingredient/id props)]))

(defsc RecipeReferenceOrIngredientUnion [this props]
  {:ident (fn [] (union-ident props))
   :query (fn [] {:recipe/id     (comp/get-query RecipeReference)
                  :ingredient/id (comp/get-query Ingredient)})}
  (if (:recipe/id props)
    (ui-recipe-reference props)
    (ui-ingredient props)))

(def ui-ref-or-ingredient (comp/factory RecipeReferenceOrIngredientUnion {:keyfn union-ident}))

(defsc RecipeLineItem [this {:recipe-line-item/keys [qty uom entity]}]
  {:ident :recipe-line-item/id
   :query [:recipe-line-item/id
           :recipe-line-item/qty
           :recipe-line-item/uom
           {:recipe-line-item/entity (comp/get-query RecipeReferenceOrIngredientUnion)}]}
  (let [item-is-recipe? (:recipe/id entity)]
    (dom/ul (str qty " " uom)
      (ui-ref-or-ingredient entity))))

(def ui-line-item (comp/factory RecipeLineItem {:keyfn :recipe-line-item/id}))

(defsc Recipe [this {:recipe/keys [name line-items]}]
  {:initial-state (fn [{:recipe/keys [id]}] {:recipe/id id}) ; needed for dynamic use
   :ident         :recipe/id
   :query         [:recipe/id
                   :recipe/name
                   {:recipe/line-items (comp/get-query RecipeLineItem)}]}
  (dom/div
    (str name)
    (dom/ul
      (mapv ui-line-item line-items))))

(def ui-recipe (comp/factory Recipe {:keyfn :recipe/id}))

(defsc RecipeList [this {:recipe-list/keys [recipes]}]
  {:query         [{:recipe-list/recipes (comp/get-query Recipe)}]
   :ident         (fn [] [:component/id ::RecipeList])
   :initial-state {:recipe-list/recipes [{} {}]}}
  (dom/div {}
    (dom/button {:onClick (fn []
                            (df/load this :recipe/all Recipe {:target [:component/id ::RecipeList :recipe-list/recipes]})
                            )} "Load")
    (dom/ul
      (mapv ui-recipe recipes))))

(ws/defcard form-pre-merge-sample
  (ct.fulcro/fulcro-card
    {::ct.fulcro/wrap-root? true
     ::ct.fulcro/root       RecipeList
     ::ct.fulcro/app        (let [process-eql (fn [eql] (async/go
                                                          (pathom-parser {} eql)))
                                  remote      (mock-http-server {:parser process-eql})]
                              {:remotes {:remote remote}})}))

