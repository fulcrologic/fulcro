(ns fulcro-devguide.state-reads.parser-2
  (:require [fulcro.client.primitives :as prim]))

(def app-state (atom {
                      :window/size  [1920 1200]
                      :friends      [[:people/by-id 1] [:people/by-id 3]]
                      :people/by-id {
                                     1 {:id 1 :name "Sally" :age 22 :married false}
                                     2 {:id 2 :name "Joe" :age 33 :married false}
                                     3 {:id 3 :name "Paul" :age 45 :married true :married-to [:people/by-id 1]}
                                     4 {:id 4 :name "Mary" :age 19 :married false}}}))

; we're going to add person to the env as we go
(defn read [{:keys [parser ast state query person] :as env} dispatch-key params]
  (letfn [(parse-friend
            ; given a person-id, parse the current query by placing that person's data in the
            ; environment, and call the parser recursively.
            [person-id]
            (if-let [person (get-in @state person-id)]
              (parser (assoc env :person person) query)
              nil))]
    (case dispatch-key
      ; These trust that a person has been found and placed in the env
      :name {:value (get person dispatch-key)}
      :id {:value (get person dispatch-key)}
      :age {:value (get person dispatch-key)}
      :married {:value (get person (:key ast))}
      ; a to-one join
      :married-to (if-let [pid (get person dispatch-key)]
                    {:value (parse-friend pid)}
                    nil)
      ; these assume we're asking for the top-level state
      :window/size {:value (get @state :window/size)}
      ; a to-many join
      :friends (let [friend-ids (get @state :friends)]
                 {:value (mapv parse-friend friend-ids)})
      nil)))

(def parser (prim/parser {:read read}))
(def query [:window/size {:friends [:name :age {:married-to [:name]}]}])
