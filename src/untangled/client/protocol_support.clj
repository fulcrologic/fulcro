(ns untangled.client.protocol-support)

(defmacro with-methods [multifn methods-map & body]
  `(do (let [old-methods-map# (atom {})]
         ;;add the new methods while keeping track of the old values
         (doseq [[dispatch# function#] ~methods-map]
           (when-let [old-function# (get-method ~multifn dispatch#)]
             (swap! old-methods-map# assoc dispatch# old-function#))
           (~'-add-method ~multifn dispatch# function#))
         ;;exec body
         ~@body
         ;;cleanup methods we added
         (doseq [[dispatch# ~'_] ~methods-map]
           (remove-method ~multifn dispatch#))
         ;;put back the old methods
         (doseq [[dispatch# function#] @old-methods-map#]
           (~'-add-method ~multifn dispatch# function#)))))
