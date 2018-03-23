(ns book.ui.hover-example
  (:require
    [fulcro.client.cards :refer [defcard-fulcro]]
    [fulcro.client.mutations :refer [defmutation]]
    [fulcro.client.primitives :as prim :refer [defsc InitialAppState initial-state]]
    [fulcro.client.dom :as dom]))

(defn change-size*
  "Change the size of the canvas by some (pos or neg) amount.."
  [state-map amount]
  (let [current-size (get-in state-map [:child/by-id 0 :size])
        new-size     (+ amount current-size)]
    (assoc-in state-map [:child/by-id 0 :size] new-size)))

; Make the canvas smaller. This will cause
(defmutation ^:intern make-smaller [p]
  (action [{:keys [state]}]
    (swap! state change-size* -20)))

(defmutation ^:intern make-bigger [p]
  (action [{:keys [state]}]
    (swap! state change-size* 20)))

(defmutation ^:intern update-marker [{:keys [coords]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [:child/by-id 0 :marker] coords)))


(defn event->dom-coords
  "Translate a javascript evt to a clj [x y] within the given dom element."
  [evt dom-ele]
  (let [cx (.-clientX evt)
        cy (.-clientY evt)
        BB (.getBoundingClientRect dom-ele)
        x  (- cx (.-left BB))
        y  (- cy (.-top BB))]
    [x y]))

(defn event->normalized-coords
  "Translate a javascript evt to a clj [x y] within the given dom element as normalized (0 to 1) coordinates."
  [evt dom-ele]
  (let [cx (.-clientX evt)
        cy (.-clientY evt)
        BB (.getBoundingClientRect dom-ele)
        w  (- (.-right BB) (.-left BB))
        h  (- (.-bottom BB) (.-top BB))
        x  (/ (- cx (.-left BB))
             w)
        y  (/ (- cy (.-top BB))
             h)]
    [x y]))

(defn render-hover-and-marker
  "Render the graphics in the canvas. Pass the component props and state. "
  [props state]
  (let [canvas             (:canvas state)
        hover              (:coords state)
        marker             (:marker props)
        size               (:size props)
        real-marker-coords (mapv (partial * size) marker)
        ; See HTML5 canvas docs
        ctx                (.getContext canvas "2d")
        clear              (fn []
                             (set! (.-fillStyle ctx) "white")
                             (.fillRect ctx 0 0 size size))
        drawHover          (fn []
                             (set! (.-strokeStyle ctx) "gray")
                             (.strokeRect ctx (- (first hover) 5) (- (second hover) 5) 10 10))
        drawMarker         (fn []
                             (set! (.-strokeStyle ctx) "red")
                             (.strokeRect ctx (- (first real-marker-coords) 5) (- (second real-marker-coords) 5) 10 10))]
    (.save ctx)
    (clear)
    (drawHover)
    (drawMarker)
    (.restore ctx)))

(defn place-marker
  "Update the marker in app state. Derives normalized coordinates, and updates the marker in application state."
  [child evt]
  (prim/transact! child `[(update-marker
                            {:coords ~(event->normalized-coords evt (prim/get-state child :canvas))})]))

(defn hover-marker
  "Updates the hover location of a proposed marker using canvas coordinates. Hover location
   is stored in component local state (meaning that a low-level app database query will not
   run to do the render that responds to this change)"
  [child evt]
  (let [current-state  (prim/get-state child)
        updated-coords (event->dom-coords evt (:canvas current-state))
        new-state      (assoc current-state :coords updated-coords)]
    (prim/set-state! child new-state)
    (render-hover-and-marker (prim/props child) new-state)))

(defsc Child [this {:keys [id size]}]
  {:query          [:id :size :marker]
   :initial-state  (fn [_] {:id 0 :size 50 :marker [0.5 0.5]})
   :ident          (fn [] [:child/by-id id])
   :initLocalState (fn [] {:coords [-50 -50]})}
  ; Remember that this "render" just renders the DOM (e.g. the canvas DOM element). The graphical
  ; rendering within the canvas is done during event handling.
  ; size comes from props. Transactions on size will cause the canvas to resize in the DOM
  (dom/canvas #js {:width       (str size "px")
                   :height      (str size "px")
                   :onMouseDown (fn [evt] (place-marker this evt))
                   :onMouseMove (fn [evt] (hover-marker this evt))
                   ; This is a pure React mechanism for getting the underlying DOM element.
                   ; Note: when the DOM element changes this fn gets called with nil
                   ; (to help you manage memory leaks), then the new element
                   :ref         (fn [r]
                                  (when r
                                    (prim/update-state! this assoc :canvas r)
                                    (render-hover-and-marker (prim/props this) (prim/get-state this))))
                   :style       #js {:border "1px solid black"}}))

(def ui-child (prim/factory Child))

(defsc Root [this {:keys [child]}]
  {:query         [{:child (prim/get-query Child)}]
   :initial-state (fn [params] {:ui/react-key "K" :child (initial-state Child nil)})}
  (dom/div nil
    (dom/button #js {:onClick #(prim/transact! this `[(make-bigger {})])} "Bigger!")
    (dom/button #js {:onClick #(prim/transact! this `[(make-smaller {})])} "Smaller!")
    (dom/br nil)
    (dom/br nil)
    (ui-child child)))


