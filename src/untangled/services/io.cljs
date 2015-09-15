(ns untangled.services.io)

(defprotocol AsyncIo
  (save [this uri goodfn badfn m] "Save data, save is treated as an upsert")
  (delete [this uri goodfn badfn id] "Delete data with a specific id")
  (fetch [this uri goodfn badfn id] "Get a specific data item")
  (query [this uri goodfn badfn] "Get a list of data items")
  )

