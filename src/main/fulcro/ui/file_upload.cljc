(ns fulcro.ui.file-upload
  "DEPRECATED: See the external file upload demo instead."
  (:require
    [clojure.string :as str]
    [com.stuartsierra.component :as component]
    [cognitect.transit :as ct]
    #?(:cljs [goog.events :as events])
    #?(:cljs [fulcro.client.dom :as dom]
       :clj
    [fulcro.client.dom-server :as dom])
    [fulcro.client.primitives :as prim :refer [defui defsc]]
    [fulcro.transit :as t]
    [fulcro.client :as fc]
    [fulcro.ui.forms :as f]
    [fulcro.ui.icons :as icons]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.client.network :as net]
    [fulcro.logging :as log]
    #?(:clj
    [ring.util.response :as resp])
    #?(:clj
    [ring.util.request :as ru])
    [fulcro.client.impl.protocols :as omp]
    [clojure.set :as set]
    [fulcro.client.util :as util])
  (:refer-clojure :exclude [send])
  #?(:cljs (:import [goog.net XhrIo EventType])))

;; Server-side
#?(:clj
   (defprotocol IFileUpload
     (upload-prefix [this] "Returns the exact URI at which to install the file upload POST handler.")
     (is-allowed? [this request] "Return true if the given file upload request is acceptable (e.g. authorized)")
     (store [this file] "Save the given file, which is a map containing the filename, content-type, size, and tempfile. Return an ID that this file will be known by with respect to this file upload component.")
     (retrieve ^java.io.File [this id] "Return a java.io.File with the content that was previously stored. Id is what store originally returned.")
     (delete [this id] "Ensure that the space consumed by file with id is no reclaimed.")))

#?(:clj
   (defn handle-file-upload
     "Request handler for file uploads. See `wrap-file-upload` for a middleware version of this."
     [req upload]
     (when-not (is-allowed? upload req)
       (throw (ex-info "Upload denied" {})))
     (let [file    (get-in req [:params "file"])
           str-id  (get-in req [:params "id"])
           id      (util/transit-str->clj str-id)
           real-id (store upload file)]
       (ring.util.response/response {::prim/tempids {id real-id}}))))

#?(:clj
   (defn wrap-file-upload
     "Ring middleware. Place in your ring stack above wrap

     (-> (wrap-file-upload upload-component)
         middleware/wrap-transit-params
         middleware/wrap-transit-response
         wrap-content-type
         wrap-params
         wrap-multipart-params)

     You'll need to create (probably as a component) an upload component that
     implements the FileUpload protocol (shown as `upload-component` in the example).
     "
     [h upload]
     (let [prefix          (upload-prefix upload)
           is-file-upload? (fn [req] (= (ru/path-info req) prefix))]
       (fn [r]
         (if (is-file-upload? r)
           (handle-file-upload r upload)
           (h r))))))

(declare File cancel-file-upload add-file)

(defn file-upload-ident
  "Given a file upload control's ID, returns the ident for that upload control."
  [id] [:fulcro.ui.file-upload/by-id id])

(defn file-ident [id]
  "Given a upload file's ID, returns the ident for that file"
  [:fulcro.ui.file-upload.file/by-id id])

(defmethod f/form-field-valid? `upload-complete?
  [_ value params]
  (= value :done))

(defn cropped-name [name maxlen]
  (if (< maxlen #?(:clj  (.length name)
                   :cljs (.-length name)))
    (str (.substring name 0 maxlen) "...")
    name))

(defui ^:once File
  static f/IForm
  (form-spec [this] [(f/id-field :file/id)
                     (f/text-input :file/name)
                     (assoc (f/radio-input :file/status #{:done :transfer-in-progress :failed})
                       :input/validator `upload-complete?)])
  static prim/IQuery
  (query [this] [f/form-key :file/id :file/name :file/size :file/progress :file/status :ui/js-file])
  static prim/Ident
  (ident [this props] (file-ident (:file/id props)))
  Object
  (render [this]
    (let [renderFile (prim/get-computed this :renderFile)
          onRetry    (fn [] (log/info "User asked to retry upload"))
          onCancel   (prim/get-computed this :onCancel)     ; TODO: Unhappy path
          r          (prim/get-reconciler this)
          s          (prim/app-state r)
          {:keys [file/id file/name file/size file/progress file/status] :as props} (prim/props this)
          label      (cropped-name name 20)]
      (if renderFile
        (renderFile this)
        (dom/li {:key (str "file-" id)} (str label " (" size " bytes) ")
          (case status
            :failed (dom/span nil "FAILED!")
            :done (dom/span nil "Ready.")
            (dom/span nil "Sending..." progress "%"))
          (dom/button {:onClick #(onCancel id)}
            (icons/icon :cancel)))))))

(defn get-js-file
  "Returns a js/File object for the given file input properties. Useful if you want
  to do some ui-related operations, such as showing a preview of the file."
  [file-props]
  (-> file-props :ui/js-file meta :js-file))

(def ui-file
  "Render a file that is to be (or is in the process of being) uploaded."
  (prim/factory File {:keyfn :file/id}))

#?(:clj (def clj->js identity))

(defn current-files
  "Returns the current set of files that a file upload component is managing."
  [file-upload]
  (:file-upload/files file-upload))

(defn clear-upload-list-impl
  "Implementation for a mutation that clears the file list on a file upload input by its ID."
  [state-map id]
  (assoc-in state-map (conj (file-upload-ident id) :file-upload/files) []))

(defui ^:once FileUploadInput
  static f/IForm
  (form-spec [this] [(f/id-field :file-upload/id)
                     (f/subform-element :file-upload/files File :many)])
  static prim/InitialAppState
  (initial-state [cls {:keys [id]}] (f/build-form FileUploadInput {:file-upload/id id :file-upload/files []}))
  static prim/IQuery
  (query [this] [f/form-key f/form-root-key :file-upload/id
                 {:file-upload/files (prim/get-query File)}])
  static prim/Ident
  (ident [this props] (file-upload-ident (:file-upload/id props)))
  Object
  (render [this]
    (let [{:keys [file-upload/id file-upload/files] :as props} (prim/props this)
          {:keys [accept multiple? renderControl renderFile]} (prim/get-computed this)
          file-upload-id id
          control-id     (str "file-upload-" id)
          onCancel       (fn [id] (prim/transact! this `[(cancel-file-upload {:upload-id ~file-upload-id
                                                                              :file-id   ~id})
                                                         ~f/form-root-key]))
          onChange       (fn [evt]
                           (let [js-file-list (.. evt -target -files)]
                             (prim/transact! this
                               (conj (mapv (fn [file-idx]
                                             (let [fid     (prim/tempid)
                                                   js-file (.item js-file-list file-idx)
                                                   tx-call `(add-file ~{:file-upload file-upload-id
                                                                        :file-id     fid
                                                                        :js-file     js-file})]
                                               tx-call)) (range (.-length js-file-list)))
                                 f/form-root-key))))
          can-add-more?  (or (empty? files) multiple?)
          attrs          (cond-> {:onChange (fn [evt] (onChange evt))
                                  :id       control-id
                                  :style    {:display "none"}
                                  :value    ""
                                  :type     "file"}
                           accept (assoc :accept accept)
                           multiple? (assoc :multiple "multiple"))]
      (dom/div nil
        (when (seq files)
          (dom/ul {:className "file-upload-list"} (mapv #(ui-file (prim/computed % {:onCancel onCancel :renderFile renderFile})) files)))
        (when can-add-more?
          (if renderControl
            (renderControl onChange accept multiple?)
            (dom/label {:htmlFor control-id} "Add Files" (dom/input attrs))))))))

(def ui-file-upload
  "Namespace DEPRECATED.

  Render a file upload component. Typically declared and embedded in a form. All of these parameters can
  be passed through f/form-field, which is the normal way to render this control when using forms.

  Allowed customization props (through computed):

  :accept - The MIME types (comma-separated string) allowed.

  :multiple? - If the upload should let the user select multiple files.

  :renderFile - A custom `(fn [upload-id file-props] DOM)` to render the files that selected for upload.
  `upload-id` is the ID of the file upload control that owns the file.
   See fulcro.ui.file-upload/File's query for details of available file-props. Can invoke
   the `cancel-file-upload` mutation to cancel an upload (which is why you need the upload-id).

  :renderControl - A custom `(fn [onChange accept multiple?] DOM)` that will render the DOM for the control that appears to allow
  users to add files. Must output at least an `input` of type `file` with onChange set to the function it
  receives. If set, `accept` is the acceptable MIME types, and `multiple?` is if the control should allow more
  than one file to be selected. The upload input UI component will hide the control if it is not multiple and
  a file has been selected."
  (prim/factory FileUploadInput {:keyfn :file-upload/id}))

(defmethod f/form-field* ::f/file-upload [component form field & params]
  (let [{:keys [id name] :as upload-data} (f/current-value form field)
        {:keys [uri]} params]
    (ui-file-upload (prim/computed upload-data (or params {})))))

(defn progress%
  "Namespace DEPRECATED.
   Given a XhrIo network progress event, returns a number between 0 and 100 to indicate progress."
  [progress-evt] (int (* 100 (/ (or (.-loaded progress-evt) 0) (or (.-total progress-evt) 1)))))

(defprotocol Abort
  (abort-send [this id] "Abort the send with the given ID."))

(defrecord FileUploadNetwork [reconciler active-transfers transfers-to-skip upload-url]
  net/ProgressiveTransfer
  (updating-send [this edn ok error update-fn]
    #?(:cljs
       (try
         (when-not @reconciler
           (log/error "File upload networking does not have the reconciler. In your started-callback, please call `fulcro.ui.file-upload/install-reconciler!`."))
         (let [state (prim/app-state @reconciler)]
           (doseq [call edn]
             (let [action     (-> call first)
                   params     (-> call second)
                   js-file    (:js-file params)
                   id         (:file-id params)
                   is-add?    (= action `add-file)
                   is-cancel? (= action `cancel-file-upload)]
               (cond
                 (and is-add? (@transfers-to-skip id)) (do
                                                         (swap! transfers-to-skip disj id)
                                                         (ok {}))

                 is-cancel? (abort-send this id)
                 is-add? (let [xhrio        (XhrIo.)
                               done-fn      (fn [edn]
                                              (let [ident    (file-ident id)
                                                    real-id  (or
                                                               (get-in edn [`add-file ::prim/tempids id])
                                                               (get-in edn [`add-file :tempids id])
                                                               id)
                                                    file-obj (get-in @state ident)
                                                    file     (assoc file-obj :file/id real-id :file/progress 100 :file/status :done)]
                                                ; force update of forms at completion of upload, so validation states can update
                                                (omp/queue! @reconciler [f/form-root-key])
                                                (ok {ident file `add-file edn} [{ident (prim/get-query File)} `(add-file)])))
                               progress-fn  (fn [evt]
                                              (let [ident    (file-ident id)
                                                    file-obj (get-in @state ident)
                                                    file     (assoc file-obj :file/progress (progress% evt))]
                                                (update-fn {ident file} {ident (prim/get-query File)})))
                               error-fn     (fn [evt]
                                              (let [ident    (file-ident id)
                                                    file-obj (get-in @state ident)
                                                    file     (assoc file-obj :file/progress 0 :file/status :failed)]
                                                (update-fn {ident file} {ident (prim/get-query File)}))
                                              (error evt))
                               with-dispose (fn [f] (fn [arg] (try
                                                                (f arg)
                                                                (finally
                                                                  (swap! active-transfers dissoc id)
                                                                  (.dispose xhrio)))))
                               form         (js/FormData.)]
                           (swap! active-transfers assoc id xhrio)
                           (.append form "file" js-file)
                           (.append form "id" (util/transit-clj->str id))
                           (.setProgressEventsEnabled xhrio true)
                           (events/listen xhrio (.-SUCCESS EventType) (with-dispose #(done-fn (ct/read (t/reader {}) (.getResponseText xhrio)))))
                           (events/listen xhrio (.-UPLOAD_PROGRESS EventType) #(progress-fn %))
                           (events/listen xhrio (.-ERROR EventType) (with-dispose #(error-fn %)))
                           (.send xhrio upload-url "POST" form #js {}))))))
         (catch js/Object e (log/error "NETWORKING THREW " e)
                            (error e)))))
  Abort
  (abort-send [this file-id]
    (if-let [net (get @active-transfers file-id)]
      (.abort net)
      (swap! transfers-to-skip conj file-id)))
  net/NetworkBehavior
  (serialize-requests? [this] false)
  net/FulcroNetwork
  (send [this edn ok-callback error-callback]
    (net/updating-send this edn ok-callback error-callback identity))
  (start [this] this))

(defn install-reconciler! [file-upload-networking reconciler]
  (reset! (:reconciler file-upload-networking) reconciler))

(defn file-upload-networking
  "Namespace DEPRECATED.
   Create an instance of a file upload networking object. You should install one of these as the
  `:file-upload` remote in your fulcro client. Upload url defaults to /file-upload, but can
  be configured (here and on the server)"
  ([] (file-upload-networking "/file-upload"))
  ([upload-url] (map->FileUploadNetwork {:upload-url        (or upload-url "/file-upload")
                                         :active-transfers  (atom {})
                                         :transfers-to-skip (atom #{})
                                         :reconciler        (atom nil)})))

#?(:cljs
   (defmutation cancel-file-upload
     "Namespace DEPRECATED.
     mutation: Cancels the current file upload. `upload-id` is the ID of the file-upload control, and
     `file-id` is the ID of the file to cancel."
     [{:keys [upload-id file-id]}]
     (action [{:keys [state]}]
       (let [files-path   (conj (file-upload-ident upload-id) :file-upload/files)]
         (swap! state (fn [st] (-> st
                                 (update :file-upload-file/by-id dissoc file-id)
                                 (m/remove-ident* (file-ident file-id) files-path))))))
     (file-upload [env] true)))

#?(:cljs
   (defmutation add-file
     "Namespace DEPRECATED.
     Add a file to the given file-upload (id) component. `file-id` should be a tempid.
      The name, size, and js-file should be taken from the js/FileList event that the file input
      gives on change events."
     [{:keys [file-upload file-id name size js-file]}]
     (action [{:keys [state target]}]
       (swap! state (fn [st] (-> st
                               (fc/integrate-ident (file-ident file-id)
                                 :append (conj (file-upload-ident file-upload) :file-upload/files))
                               (assoc-in (file-ident file-id) (f/build-form File
                                                                {:file/id       file-id
                                                                 :file/name     (.-name js-file)
                                                                 :file/size     (.-size js-file)
                                                                 :file/progress 0
                                                                 ; IMPORTANT: not serializable as a prop.
                                                                 ; but meta-data does not need to be.
                                                                 :ui/js-file    (with-meta {} {:js-file js-file})
                                                                 :file/status   :transfer-in-progress}))))))
     (file-upload [env] true)))

(defn file-upload-input
  "
  Namespace DEPRECATED.

  Declare (in a form-spec) a field that represents file upload(s).

  `accept`: (optional) String of comma-separated mime types that the files can have.
  `multiple?`: (optional) True/false. Can the user upload multiple files? (default false)
  "
  [name & {:keys [accept multiple? className]}]
  (assoc (f/subform-element name FileUploadInput :one)
    :input/accept accept
    :input/multiple? (boolean multiple?)
    :input/css-class className
    :input/type ::f/file-upload))
