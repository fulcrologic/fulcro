(ns fulcro-todomvc.ui
  (:require
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.react-interop :as interop]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [button div h3 label ul]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.rendering.keyframe-render :as keyframe]))

(defsc Car [this {:car/keys [id model]}]
  {:query         [:car/id :car/model]
   :ident         :car/id
   :initial-state {:car/id    :param/id
                   :car/model :param/model}}
  (js/console.log "Render car" id)
  (div {} "Model: " model))

(def ui-car (comp/factory Car {:keyfn :car/id}))

(defmutation make-older [{:person/keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:person/id id :person/age] inc)))

(defsc Person [this {:person/keys [id name age cars] :as props}]
  {:query             [:person/id :person/name :person/age {:person/cars (comp/get-query Car)}]
   :ident             :person/id
   :initial-state     {:person/id   :param/id
                       :person/name :param/name
                       :person/age  20
                       :person/cars [{:id 40 :model "Leaf"}
                                     {:id 41 :model "Escort"}
                                     {:id 42 :model "Sienna"}]}
   :componentDidMount (fn [this] (let [p (comp/props this)]
                                   (js/console.log "Mounted" p)))
   :initLocalState    (fn [this {:person/keys [id name age cars] :as props}]
                        {:anything :can-be-added-here
                         :onClick  (fn [evt]
                                     (comp/transact! this [(make-older {:person/id id})])
                                     (js/console.log "Made" name "older from cached function"))})}
  (js/console.log "Render person" id)
  (let [onClick (comp/get-state this :onClick)]
    (div :.ui.segment {}
      (div :.ui.form {}
        (div :.field {}
          (label {} "Name: ")
          name)
        (div :.field {}
          (label {} "Amount:"))
        (div :.field {}
          (label {:onClick onClick} "Age: ")
          age))
      (button {:onClick #(do (comp/transact! this [(make-older {:person/id id})])
                             (js/console.log "Made" name "older from inline function"))}
        "Make older")
      (h3 {} "Cars:")
      (ul {}
        (map ui-car cars)))))

(def ui-person (comp/factory Person {:keyfn :person/id}))

;; We have only one PersonList in the app, therefore we don't need an id for the list.
(defsc PersonList [this {:person-list/keys [people] :as props}]
  {:query         [{:person-list/people (comp/get-query Person)}]
   :ident         (fn [_ _] [:component/id ::person-list])
   :initial-state {:person-list/people [{:id 1 :name "Bob"}
                                        {:id 2 :name "Sally"}]}}
  (js/console.log "Render person list")
  (div {}
    (h3 {} "People")
    (map ui-person people)))


(def ui-person-list (comp/factory PersonList))

(defsc Root [this {:root/keys [list] :as props}]
  {:query         [{:root/list (comp/get-query PersonList)}]
   :initial-state {:root/list {}}}
  (js/console.log "Render root")
  (div {}
    (h3 {} "Application")
    (ui-person-list list)))

