(ns fulcro.ui.clip-tool
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.client :as fc]
            [fulcro.ui.clip-geometry :as cg]))

(defn refresh-clip-region [this props]
  (let [{:keys [url size handle-size] :or {handle-size 10}} props
        {:keys [width height]} size
        {:keys [canvas image-object clip-region]} (prim/get-state this)
        aspect-ratio (/ (.-width image-object) (.-height image-object))
        w            (-> props :size :width)
        h            (/ w aspect-ratio)
        ctx          (.getContext canvas "2d")
        ul-handle    (cg/new-handle (:ul clip-region) handle-size)
        lr-handle    (cg/new-handle (:lr clip-region) handle-size)]
    (when (and url (not= url (.-src image-object)))
      (set! (.-src image-object) url))
    (cg/draw-rect ctx (cg/->Rectangle (cg/->Point 0 0) (cg/->Point width height)) :solid-white)
    (.drawImage ctx image-object 0 0 w h)
    (cg/draw-rect ctx clip-region :solid)
    (cg/draw-rect ctx ul-handle :solid-black)
    (cg/draw-rect ctx lr-handle :solid-black)))

(defn translate-clip-region
  "Convert a clip region from clip tool coordinates to image coordinates. The size is the size of the clip tool."
  [clip-region size image-object]
  (let [{:keys [ul lr]} clip-region
        img-w             (.-width image-object)
        img-h             (.-height image-object)
        img-aspect        (/ img-w img-h)
        tool-bbox         (cg/->Rectangle (cg/->Point 0 0) (cg/->Point (:width size) (:height size)))
        scaled-image-bbox (cg/max-rect tool-bbox img-aspect)
        w                 (cg/width scaled-image-bbox)
        scale             (/ img-w w)
        ul-x              (* scale (:x ul))
        ul-y              (* scale (:y ul))
        lr-x              (* scale (:x lr))
        lr-y              (* scale (:y lr))]
    (cg/->Rectangle (cg/->Point ul-x ul-y) (cg/->Point lr-x lr-y))))

(defn generate-url [id clip-region size image-object]
  (let [image-clip-area (translate-clip-region clip-region size image-object)
        ul              (:ul image-clip-area)
        lr              (:lr image-clip-area)]
    (str "/assets/" id "/?x1=" (:x ul) "&y1=" (:y ul) "&x2=" (:x lr) "&y2=" (:y lr) "&width=" (+ (cg/width clip-region) 10))
    ))

(defn constrain-size [old-clip min-size new-clip]
  (let [w-new-clip (cg/width new-clip)
        h-new-clip (cg/height new-clip)]
    (if (or (> min-size w-new-clip) (> min-size h-new-clip)) old-clip new-clip)))

(defn change-cursor [canvas cursor-type]
  (set! (.-cursor (.-style canvas)) cursor-type))

(defn constrain-corner [^cg/Rectangle orig-clip ^cg/Rects new-clip aspect-ratio]
  (let [ul-new     (:ul new-clip)
        lr-new     (:lr new-clip)
        ul-old     (:ul orig-clip)
        lr-old     (:lr orig-clip)
        dw         (- (cg/width orig-clip) (cg/width new-clip))
        dh         (- (cg/height orig-clip) (cg/height new-clip))
        ul-moving? (or (not= ul-new ul-old))
        dx         (* dh aspect-ratio)
        dy         (/ dw aspect-ratio)]
    (if ul-moving?
      (if (> (Math/abs dw) (Math/abs dh))
        (cg/->Rectangle (cg/->Point (:x ul-new) (+ dy (:y ul-old))) lr-old)
        (cg/->Rectangle (cg/->Point (+ dx (:x ul-old)) (:y ul-new)) lr-old))
      (if (> (Math/abs dw) (Math/abs dh))
        (cg/->Rectangle ul-old (cg/->Point (:x lr-new) (- (:y lr-old) dy)))
        (cg/->Rectangle ul-old (cg/->Point (- (:x lr-old) dx) (:y lr-new)))))))

(defn dragUL [comp evt]
  (let [{:keys [canvas clip-region aspect-ratio min-size origin]} (prim/get-state comp)
        {:keys [ul lr]} clip-region
        target   (cg/event->dom-coords evt canvas)
        new-ul   (cg/diff-translate ul origin target)
        new-clip (constrain-size clip-region min-size (constrain-corner clip-region (cg/->Rectangle new-ul (:lr clip-region)) aspect-ratio))]
    (change-cursor canvas "nw-resize")
    (prim/update-state! comp assoc :origin target :clip-region new-clip)))

(defn dragLR [comp evt]
  (let [{:keys [canvas clip-region aspect-ratio min-size origin]} (prim/get-state comp)
        {:keys [ul lr]} clip-region
        target   (cg/event->dom-coords evt canvas)
        new-lr   (cg/diff-translate lr origin target)
        new-clip (constrain-size clip-region min-size (constrain-corner clip-region (cg/->Rectangle (:ul clip-region) new-lr) aspect-ratio))]
    (change-cursor canvas "nw-resize")
    (prim/update-state! comp assoc :origin target :clip-region new-clip)))

(defn pan [comp evt]
  (let [{:keys [canvas clip-region origin]} (prim/get-state comp)
        target   (cg/event->dom-coords evt canvas)
        new-clip (cg/diff-translate-rect clip-region origin target)]
    (change-cursor canvas "move")
    (prim/update-state! comp assoc :origin target :clip-region new-clip)))

(defn mouseDown [this evt]
  (let [{:keys [canvas clip-region handle-size]} (prim/get-state this)
        canvas-point (cg/event->dom-coords evt canvas)
        ul-handle    (cg/new-handle (:ul clip-region) handle-size)
        lr-handle    (cg/new-handle (:lr clip-region) handle-size)]
    (cond
      (cg/inside-rect? ul-handle canvas-point) (prim/update-state! this assoc :active-operation :drag-ul :origin canvas-point)
      (cg/inside-rect? lr-handle canvas-point) (prim/update-state! this assoc :active-operation :drag-lr :origin canvas-point)
      (cg/inside-rect? clip-region canvas-point) (prim/update-state! this assoc :active-operation :pan :origin canvas-point))
    (refresh-clip-region this (prim/props this))))

(defn mouseUp [this evt]
  (let [{:keys [canvas]} (prim/get-state this)]
    (set! (.-cursor (.-style canvas)) "")
    (prim/update-state! this assoc :active-operation :none :origin nil)
    (refresh-clip-region this (prim/props this))))

(defn mouseMoved [this evt onChange]
  (let [{:keys [active-operation]} (prim/get-state this)
        {:keys [size]} (prim/props this)]
    (case active-operation
      :drag-ul (dragUL this evt)
      :drag-lr (dragLR this evt)
      :pan (pan this evt)
      nil)
    (when (and onChange (not= active-operation :none))
      (let [{:keys [clip-region image-object] :as state} (prim/get-state this)]
        (onChange (assoc state :clip-region (translate-clip-region clip-region size image-object)))))
    (refresh-clip-region this (prim/props this))))

(defn set-initial-clip [comp img]
  (let [{:keys [aspect-ratio canvas]} (prim/get-state comp)
        canvas-bbox (cg/->Rectangle (cg/->Point 0 0) (cg/->Point (.-width canvas) (.-height canvas)))
        img-aspect  (/ (.-width img) (.-height img))
        img-bbox    (cg/max-rect canvas-bbox img-aspect)
        clip        (cg/max-rect img-bbox aspect-ratio)]
    (prim/update-state! comp assoc :clip-region clip)))

(defsc ClipTool [this {:keys [id size]}]
  {:initial-state (fn [{:keys [image-url id aspect-ratio handle-size width height]
                        :or   {id "clip-1" aspect-ratio 1 width 400 height 400 handle-size 10} :as params}]
                    {:id           id
                     :url          image-url
                     :aspect-ratio aspect-ratio
                     :handle-size  handle-size
                     :size         {:width width :height height}})
   :query         [:id :url :size :aspect-ratio :handle-size]
   :ident         [:clip-tools/by-id :id]
   :protocols     [Object
                   (initLocalState [this]
                     (let [img (js/Image.)]
                       (set! (.-onload img) (fn []
                                              (set-initial-clip this img)
                                              (let [{:keys [size]} (prim/props this)
                                                    onChange (prim/get-computed this :onChange)
                                                    {:keys [clip-region]} (prim/get-state this)]
                                                (when onChange (onChange (assoc (prim/get-state this) :clip-region (translate-clip-region clip-region size img)))))
                                              (refresh-clip-region this (prim/props this))))
                       {:image-object    img
                        :origin          (cg/->Point 0 0)
                        :clip-region     (cg/->Rectangle (cg/->Point 0 0)
                                           (cg/->Point 0 0))
                        :activeOperation :none
                        :min-size        20}))
                   (shouldComponentUpdate [this next-props next-state] false)
                   (componentWillReceiveProps [this props] (refresh-clip-region this props)) ; for URL changes
                   (componentDidMount [this]
                     (let [{:keys [url handle-size aspect-ratio size]} (prim/props this)
                           {:keys [image-object clip-region] :as state} (prim/get-state this)]
                       (prim/update-state! this assoc :aspect-ratio aspect-ratio :handle-size (or handle-size 10))
                       (set! (.-src image-object) url)))]}
  (let [onChange (prim/get-computed this :onChange)]
    (dom/div #js {:style #js {:width "500px"}}
      (dom/canvas #js {:ref         (fn [ele] (when ele (prim/update-state! this assoc :canvas ele)))
                       :id          id
                       :width       (str (:width size) "px")
                       :height      (str (:height size) "px")
                       :onMouseDown (fn [evt] (mouseDown this evt))
                       :onMouseMove (fn [evt] (mouseMoved this evt onChange))
                       :onMouseUp   (fn [evt] (mouseUp this evt))
                       :className   "clip-tool"}))))

(def ui-clip-tool (prim/factory ClipTool))

(defn refresh-image [canvas component]
  (when (-> component prim/props :image-object)
    (let [props        (prim/props component)
          {:keys [clip-region image-object]} props
          sx           (-> clip-region :ul :x)
          sy           (-> clip-region :ul :y)
          sw           (cg/width clip-region)
          sh           (cg/height clip-region)
          aspect-ratio (/ sw sh)
          w            (-> props :width)
          h            (/ w aspect-ratio)
          ctx          (.getContext canvas "2d")]
      (cg/draw-rect ctx (cg/->Rectangle (cg/->Point 0 0) (cg/->Point w h)) :solid-black)
      (.drawImage ctx image-object sx sy sw sh 0 0 w h))))

(defsc PreviewClip [this {:keys [filename width height clip-region]}]
  {}
  (let [{:keys [ul lr]} clip-region]
    (dom/div #js {:style #js {:position "relative" :top "-400px" :left "500px"}}
      (dom/canvas #js {:ref       (fn [elem] (when elem
                                               (refresh-image elem this)))
                       :style     #js {:border "1px solid black"}
                       :width     (str width "px")
                       :height    (str height "px")
                       :className "preview-clip"})
      (dom/div nil (str filename
                     "?x1=" (-> ul :x int)
                     "&y1=" (-> ul :y int)
                     "&x2=" (-> lr :x int)
                     "&y2=" (-> lr :y int)
                     "&width=" width)))))

(def ui-preview-clip
  "Render a preview of a clipped image. "
  (prim/factory PreviewClip))
