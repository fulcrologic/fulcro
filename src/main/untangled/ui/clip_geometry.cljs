(ns untangled.ui.clip-geometry
  (:require [untangled.client.logging :as log]))

(defrecord Point [x y])
(defrecord Rectangle [ul lr])

(defn width [^Rectangle rect]
  (- (-> rect :lr :x) (-> rect :ul :x)))

(defn height [^Rectangle rect]
  (- (-> rect :lr :y) (-> rect :ul :y)))

(defn event->dom-coords
  "Translate a javascript evt to a clj {:x x :y y} map within the given dom element."
  [evt dom-ele]
  (let [cx (.-clientX evt)
        cy (.-clientY evt)
        BB (.getBoundingClientRect dom-ele)
        x  (- cx (.-left BB))
        y  (- cy (.-top BB))]
    (Point. x y)))

(defn inside-rect?
  "Check if the rectangle (with :ul and :lr) contains the given coord (:x :y)"
  [^Rectangle rect ^Point coords]
  (and
    (>= (:x coords) (-> rect :ul :x))
    (>= (:y coords) (-> rect :ul :y))
    (<= (:x coords) (-> rect :lr :x))
    (<= (:y coords) (-> rect :lr :y))))

(defn rect-midpoint [^Rectangle rect]
  (->Point (int (/ (+ (-> rect :lr :x) (-> rect :ul :x)) 2))
    (int (/ (+ (-> rect :lr :y) (-> rect :ul :y)) 2))))

(defn diff-translate
  "Return a point translated by the vector difference of target and origin as the translation (both orign and target
  are vectors drawn from (0,0))."
  [^Point point ^Point origin-vector ^Point target-vector]
  (let [dx (- (:x target-vector) (:x origin-vector))
        dy (- (:y target-vector) (:y origin-vector))]
    (->Point (+ (:x point) dx)
      (+ (:y point) dy))))

(defn new-handle
  "Creates a new control handle, centered at point, of size sz"
  [{:keys [x y] :as point} sz]
  (let [half-sz (int (/ sz 2))]
    (->Rectangle (->Point (- x half-sz) (- y half-sz))
      (->Point (+ x half-sz) (+ y half-sz)))))

(defn diff-translate-rect
  "Return a rectangle translated by the vector difference of target - origin."
  [^Rectangle rect ^Point origin ^Point target]
  (->Rectangle (diff-translate (:ul rect) origin target)
    (diff-translate (:lr rect) origin target)))

(defn draw-rect
  "Draw a rectangle with a style (:solid-white, :solid-black, :solid, or :dashed)"
  [ctx ^Rectangle rect style]
  (.save ctx)
  (let [x (-> rect :ul :x)
        y (-> rect :ul :y)
        w (- (-> rect :lr :x) (-> rect :ul :x))
        h (- (-> rect :lr :y) (-> rect :ul :y))]
    (case style
      :solid-white (do
                     (set! (.-fillStyle ctx) "white")
                     (.fillRect ctx x y w h))
      :solid-black (do
                     (set! (.-fillStyle ctx) "black")
                     (.fillRect ctx x y w h))
      :solid (do
               (.setLineDash ctx #js [0])
               (.strokeRect ctx x y w h))
      :dashed (do
                (.setLineDash ctx #js [5])
                (.strokeRect ctx x y w h))
      (log/error "ERROR: Unsupported style " style)))
  (.restore ctx))

(defn max-rect
  "Return the largest rectangle that fits in bounding-rect but has the given aspect ratio (w/h)"
  [bounding-rect aspect-ratio]
  (let [brect-aspect (/ (width bounding-rect) (height bounding-rect))]
    (if (<= brect-aspect aspect-ratio)
      (let [w (width bounding-rect)
            h (int (/ w aspect-ratio))]
        (->Rectangle (->Point 0 0) (->Point w h)))
      (let [h (height bounding-rect)
            w (int (* h aspect-ratio))]
        (->Rectangle (->Point 0 0) (->Point w h))))))

