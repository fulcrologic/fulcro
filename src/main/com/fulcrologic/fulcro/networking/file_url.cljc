(ns com.fulcrologic.fulcro.networking.file-url
  "Support for converting binary network responses to a usable File URL in a browser. This
  file is CLJC so the functions exist for SSR, but they do nothing in CLJ.")

(defn gc-file-url
  "Tells the browser to GC the space associated with an in-memory file URL"
  [url]
  #?(:cljs
     (js/window.URL.revokeObjectURL url)))

(defn raw-response->file-url
  "Convert an array-buffer network result (i.e. in a mutation result-action env) into a file URL that can be
  used live in the browser.

  `result` - The network result (e.g. the :result key from a mutation env). A map with a `:body` that is an ArrayBuffer.
  `mime-type` - The MIME type you want to associate with the file URL.

  NOTE: You should use gc-file-url to release resources when finished.

  To get a binary response use an HTTP remote and use `with-response-type` in your
  mutation's remote section:
  ```
  (remote [env] (m/with-response-type env :array-buffer)
  ```

  You will also need to modify your middleware to ensure you actually respond with
  binary data in the server.
  "
  [{:keys [body]} mime-type]
  #?(:cljs
     (let [wrapped-blob (js/Blob. (clj->js [body]) #js {:type mime-type})
           fileURL      (js/URL.createObjectURL wrapped-blob)]
       fileURL)))

(defn save-file-url-as!
  "Given a file URL and a target filename: generates a DOM link and clicks on it, which should initiate a download in
   the browser.

   ALPHA: Not tested in all browsers. Known to work in Chrome.
   "
  [file-url target-filename]
  #?(:cljs
     (let [link (js/document.createElement "a")]
       (set! (.-target link) "_blank")
       (set! (.-href link) file-url)
       (set! (.-download link) target-filename)
       (.click link))))
