(ns untangled.services.asyncio)

(defprotocol AsyncIo
  (save [this uri goodfn errorfn data] "Save data, save is treated as an upsert")
  (delete [this uri goodfn errorfn id] "Delete data with a specific id")
  (fetch [this uri goodfn errorfn id] "Get a specific data item")
  (query [this uri goodfn errorfn] "Get a list of data items")
  )

