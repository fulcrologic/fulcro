(ns untangled.server.protocols)

(defprotocol Database
  (get-connection [this] "Get a connection to this database" )
  (get-info [this]
    "Get descriptive information about this database. This will be a map that includes general information, such as the
    name and uri of the database. Useful for logging and debugging.")
  (get-db-config [this]
    "Get the config for this database, usually based on the database's name"))
