(ns fulcro-tutorial.O15-Forms-File-Upload
  (:require
    [fulcro.client.dom :as dom]
    [devcards.core :as dc :refer-macros [defcard defcard-doc]]
    [fulcro.client.primitives :as prim :refer [defui defsc]]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [fulcro.client :as fc]
    [fulcro.ui.forms :as f]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro-tutorial.N10-Twitter-Bootstrap-CSS :refer [render-example]]
    [goog.events :as events]
    [fulcro.client.network :as net]
    [clojure.string :as str]
    [fulcro.ui.file-upload :as fu :refer [FileUploadInput file-upload-input file-upload-networking]]
    [fulcro.client.logging :as log]
    [fulcro.ui.bootstrap3 :as b]
    [fulcro.client.data-fetch :as df]
    [fulcro.ui.file-upload :as upload])
  (:refer-clojure :exclude [send])
  (:import [goog.net XhrIo EventType]))

(defn field-with-label
  "A non-library helper function, written by you to help lay out your form."
  [comp form name label & params]
  (b/row {}
    (b/col {:xs 2 :htmlFor name} label)
    (b/col {:xs 10} (apply f/form-field comp form name params))))

(defui ^:once FileUploads
  static prim/InitialAppState
  (initial-state [this params]
    (f/build-form this {:db/id 1 :image-uploads (prim/get-initial-state FileUploadInput {:id :image-uploads})}))
  static f/IForm
  (form-spec [this] [(f/id-field :db/id)
                     (file-upload-input :image-uploads)])
  static prim/IQuery
  (query [this] [f/form-root-key f/form-key :db/id :text {:image-uploads (prim/get-query FileUploadInput)}])
  static prim/Ident
  (ident [this props] [:example/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [db/id] :as props} (prim/props this)
          onDone     (prim/get-computed this :onDone)
          not-valid? (not (f/would-be-valid? props))]
      (dom/div #js {:className "form-horizontal"}
        (field-with-label this props :image-uploads "Image Files:" :accept "image/*" :multiple? true
          :renderFile (fn [file-component]
                        (let [onCancel (prim/get-computed file-component :onCancel)
                              {:keys [file/id file/name file/size file/progress file/status] :as props} (prim/props file-component)
                              label    (fu/cropped-name name 20)]
                          (dom/li #js {:style #js {:listStyleType "none"} :key (str "file-" id)}
                            (str label " (" size " bytes) ") (b/glyphicon {:size "14pt" :onClick #(onCancel id)} :remove-circle)
                            (dom/br nil)
                            (case status
                              :failed (dom/span nil "FAILED!")
                              :done ""
                              (b/progress-bar {:current progress})))))
          :renderControl (fn [onChange accept multiple?]
                           (let [control-id (str "add-control-" id)
                                 attrs      (cond-> {:onChange (fn [evt] (onChange evt))
                                                     :id       control-id
                                                     :style    #js {:display "none"}
                                                     :value    ""
                                                     :type     "file"}
                                              accept (assoc :accept accept)
                                              multiple? (assoc :multiple "multiple")
                                              :always clj->js)]
                             (dom/label #js {:htmlFor control-id} (b/glyphicon {:className "btn btn-primary"} :plus)
                               (dom/input attrs)))))
        (b/button {:disabled not-valid?
                   :onClick  #(when onDone (onDone))} "Done")))))

(def ui-example (prim/factory FileUploads {:keyfn :db/id}))

(defui ^:once Image
  static prim/Ident
  (ident [this props] [:image/by-id (:db/id props)])
  ; each image has an ID and a URL. The upload-server can server images from that URL
  static prim/IQuery
  (query [this] [:db/id :image/url :ui/fetch-state])
  Object
  (render [this]
    (let [{:keys [image/url]} (prim/props this)]
      (b/col {:xs 4}
        (dom/img #js {:width "100%" :src url})))))

(def ui-image (prim/factory Image {:keyfn :db/id}))

(defn- load-images
  "A helper function to trigger a load of a page of images from the server."
  [component page]
  (df/load component :server/image-library Image {:params {:page (or page 1)}
                                                  :target [:image-library/by-id 1 :library/images]}))

(defui ^:once ImageLibrary
  static prim/InitialAppState
  (initial-state [c p] {:db/id (:id p) :ui/page 1 :library/images []})
  static prim/Ident
  (ident [this props] [:image-library/by-id (:db/id props)])
  static prim/IQuery
  (query [this] [:db/id :ui/page {:library/images (prim/get-query Image)}])
  Object
  ; Ensure that we have a page of images loaded when we're shown.  Only trigger if the content is actually empty.
  (componentDidMount [this]
    (let [{:keys [library/images ui/page]} (prim/props this)]
      (when (and (vector? images) (empty? images))
        (load-images this (or page 1)))))
  (render [this]
    (let [{:keys [db/id library/images ui/page]} (prim/props this)
          onUpload (prim/get-computed this :onUpload)]
      (dom/div nil
        (b/button {:onClick #(when onUpload (onUpload))} "Upload")
        ; When things are loading, the images will be a load marker, which is a map. We could also use lazily-loaded here.
        (if (map? images)
          (dom/p nil "Loading...")
          (let [rows (partition-all 3 images)]
            (dom/div nil
              (dom/span nil (str "Page " page))
              (when (not= 1 page)
                (b/button {:onClick (fn []
                                      (m/set-value! this :ui/page (dec page))
                                      (load-images this (dec page)))} "Prior"))
              (when (= 3 (count (last rows)))
                (b/button {:onClick (fn []
                                      (m/set-value! this :ui/page (inc page))
                                      (load-images this (inc page)))} "Next"))

              (map-indexed (fn [idx row]
                             (b/row {}
                               (mapv ui-image row))) rows))))))))

(def ui-image-library (prim/factory ImageLibrary))

(defmutation clear-upload-list
  "A mutation to clear the list of files that have been uploaded."
  [ignored]
  (action [{:keys [state]}]
    (swap! state upload/clear-upload-list-impl :image-uploads)))

(defui ^:once ImageLibraryDemo
  static prim/InitialAppState
  (initial-state [this _] {:demo          (prim/get-initial-state FileUploads {:db/id 1})
                           :image-library (prim/get-initial-state ImageLibrary {:id 1})})
  static prim/Ident
  (ident [this props] [:demo/by-id :images])
  static prim/IQuery
  (query [this] [:ui/show-library?
                 {:image-library (prim/get-query ImageLibrary)}
                 {:demo (prim/get-query FileUploads)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key ui/show-library? demo image-library]} (prim/props this)]
      ; simple DOM toggle between the two UIs. This will cause componentWillMount to trigger on the image library, which
      ; will load images if it hasn't already.
      (if show-library?
        (ui-image-library (prim/computed image-library {:onUpload #(m/toggle! this :ui/show-library?)}))
        (ui-example (prim/computed demo {:onDone (fn []
                                                   ; when moving away from the upload screen, clear the list so we don't see it again when we come back
                                                   (prim/transact! this `[(clear-upload-list {})])
                                                   (m/toggle! this :ui/show-library?))}))))))

(def ui-demo (prim/factory ImageLibraryDemo))

(defui ^:once DemoRoot
  static prim/InitialAppState
  (initial-state [this _] {:screen (prim/get-initial-state ImageLibraryDemo {})})
  static prim/IQuery
  (query [this] [:ui/react-key {:screen (prim/get-query ImageLibraryDemo)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key screen]} (prim/props this)]
      (render-example "100%" "500px"
        (dom/div #js {:key react-key}
          ; In an iframe, so embed list style via react
          (dom/style #js {} ".file-upload-list { padding-left: 0; }")
          (ui-demo screen))))))

(defcard-doc
  "
  # Forms – File Upload

  ## Setup

  There are a few steps for setting up a working file upload control:

  1. Install file upload server support in your server's Ring stack and add logic for dealing with
  forms submissions that contain uploaded files.
  2. Run the server
  3. Add file-upload networking as an extra remote in Fulcro Client
  4. Load the page through your server (not figwheel).

  This repository includes a script named `run-file-upload-server.sh`. The devcard in this file should be loaded
  from that server (port 8085 by default).

  ## Understanding File Upload

  The lifecycle of the file upload control can be tied to form interactions and submission, or can act as
  a standalone upload system (though you'll still embed it in a form, of sorts). When upload is triggered the file object
  on the client gets an ID remap from your server storage component, and you can easily inject that server component into your
  parsing environment or other server handlers to deal with the result.

  The abstract composition of a file upload into your application takes the following steps:

  - Ensuring that the server's ring middleware will encode file uploads into a temp directory and add them to the request.
  - Adding a network handler for the file uploads to the server.
  - Adding a networking remote to the client to talk to the server.

  ### Customizing the Ring Stack

  If you're using the modular server support for Fulcro, then you can build a stack that contains at least
  the following middleware: transit, API hander, file upload, and wrap-multipart-params. Other bits are also
  commonly useful.

  Here's a sample middleware component for a modular server that has been tested to work (see `upload-server.clj` for the
  complete code):

  ```
  (defrecord CustomMiddleware [middleware api-handler]
    component/Lifecycle
    (stop [this] (dissoc this :middleware))
    (start [this]
      (assoc this :middleware
                  (-> not-found
                    (MIDDLEWARE api-handler) ; Normal API handler
                    wrap-file-upload ; REMOTE HANDLER (needs transit for response)
                    middleware/wrap-transit-params
                    middleware/wrap-transit-response
                    (wrap-resource \"public\")
                    wrap-content-type
                    wrap-not-modified
                    wrap-params
                    wrap-multipart-params ; TURN UPLOADS INTO DISK FILES
                    wrap-gzip))))
  ```

  ### Adding the File Upload remote:

  The client-side setup is very simple, just add a `:networking` parameter to your client that has a map
  containing the normal remote and a file upload remote:

  ```
  ; You need a reference to the network, because it needs to be told about the reconciler in order to send progress
  ; updates
  (defonce upload-networking (fulcro.ui.file-upload/file-upload-networking))

  (new-fulcro-client
    ; Once started, tell the networking where to find the reconciler
    :started-callback (fn [{:keys [reconciler]}] (fulcro.ui.file-upload/install-reconciler! upload-networking reconciler))
    :networking {:remote      (net/make-fulcro-network \"/api\" :global-error-callback identity)
                 :file-upload upload-networking})
  ```

  ## Customizing the Rendering

  The normal form field rendering is predefined as it is for all form fields, but in the case of this control
  there are a number of elements: the button to add files, along with a mechansim to show the files that have been
  added.

  You can customize how the overall upload UI looks in a few ways.

  ### Changing the UI of the Upload Button

  The default rendering shows an upload button. Once files are selected this button possibly goes away (e.g.
  if `multiple?` is false). The button itself can be customized using the `:renderControl` computed property
  (which can be passed to the ui-file-upload as a computed prop, or through the form field rendering as an
  add-on parameter).

  The function is responsible for hooking up to a HTML file input onChange event, and invoking the
  `upload/add-file` mutation on each file that is to be added.

  The example on this page shows the details.

  ### Changing the UI of the Individual Files

  The file upload control *always* renders the current list of files in a `ul` DOM parent with the
  CSS class `file-upload-list`. By default, it places each file into this list with an `li` element.
  However, this can be customized using the `:renderFile` parameter, which should be a function
  that receives the file component and renders the correct DOM. This function will also be called during upload
  refreshes, and the `:file/progress` in props will indicate progres and `:file/status` will indicate
  if the transfer is still active. The computed props will include an `onCancel` function that you can
  call to cancel the inclusion of the file (i.e. you can hook a call to `onCancel` up to a cancel button
  in your rendering).

  Again, see the code in the demo on this page for details.

  ### Rendering Details Outside of the Control

  `(current-files upload-control)` returns the current file list. You could use this to add a file count
  to a part of the UI that is outside of the control, as long as you've got access to the control's properties
  (e.g. in the parent).

  ### Showing Image Previews

  The file component props can be used to access a low-level `js/File` object of the file you're uploading.
  This can be used to do things like show image previews of images you're uploading.

  Calling `(get-js-file file-props)` will return the `js/File` object of the file.

  From there, you can use regular React DOM tricks (e.g. `:ref`) to do the rest in a custom
  file row rendering.



  # Demo

  The following demo is an Image Upload Tool. It has two screens: the upload screen, and the library browser.
  The FileUploads screen does custom rendering for both the upload form control and file list items. Note that the file uploads
  actually happen progressively as you add them, but using tempids. Once the upload completes the tempids get remapped
  to the real IDs assigned by the server. This means you can immediately use them. Note that the form is not valid
  if uploads are in progress. If you were to submit the form, these real IDs would be part of the form delta you'd
  receive in the commit, which could let you do further database associations with them.

  The image library queries the server for uploaded images (which the server paginates). These are pulled from whatever
  you've uploaded. Note that we're using the temporary ring files in `upload_server.clj`. Obviously for demonatration
  purposes only.

  See the comments in the source for more details:
  "
  (dc/mkdn-pprint-source Image)
  (dc/mkdn-pprint-source load-images)
  (dc/mkdn-pprint-source ImageLibrary)
  (dc/mkdn-pprint-source FileUploads)
  (dc/mkdn-pprint-source ImageLibraryDemo))

(defonce upload-networking (file-upload-networking))

(defcard-fulcro form-file-upload
  "
  This card is full-stack, and uses a special server. The separate server is not necessary, but
  it makes it clearer to the reader what is related to file upload. The server-side code is in `upload_server.clj`.

  You can start the server for these demos at a CLJ REPL:

  ```
  $ lein repl
  user=> (run-upload-server)
  ```

  or with the shell script `run-file-upload-server.sh`.

  The server for these examples is on port 8085, so use this page via
  [http://localhost:8085/tutorial.html#!/fulcro_tutorial.O15_Forms_File_Upload](http://localhost:8085/tutorial.html#!/fulcro_tutorial.O15_Forms_File_Upload).
  "
  DemoRoot
  {}
  {:inspect-data true
   :fulcro       {:started-callback (fn [{:keys [reconciler]}] (fu/install-reconciler! upload-networking reconciler))
                  :networking       {:remote      (net/make-fulcro-network "/api" :global-error-callback identity)
                                     :file-upload upload-networking}}})
