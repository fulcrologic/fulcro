(ns untangled.server.database.protocols)

(defprotocol Database
  (get-connection [this] "Get a connection to this database" )
  (get-info [this]
    "Get descriptive information about this database. This will be a map that includes general information, such as the
    name and uri of the database. Useful for logging and debugging.")
  (get-db-config [this]
    "Get the config for this database, usually based on the database's name")
  )

(defprotocol DatabaseResolver
  "
  # DatabaseResolver

  A protocol for generalizing access to databases of a single kind. This protocol isolates the sharding and location
  of databases so that the information needed about a database can be found in a consistent but
  decoupled manner.

  See the datahub.database.Database protocol for the generic type that will be resolved. The user of this resolver can safely
  assume that the databases returned from resolvers will conform to the Database protocol.

  Typically you will have some set of well-known database resolvers. For example and authorization database resolver
  and an account database resolver. These will be well-known, but abstract, components.

  In various circumstances (e.g. during a web request) you will need to look up a database using one of these resolvers.
  the `resolve` method will do so based on some (abstract) knowledge that is encoded on the web request. Thus the user
  of the resolver need only know two things: the resolver component and the web request. The actual location of the
  database is hidden behind mechanism that can be configured by lower levels of software.

  The `find-database` method is for circumstances where you know, specifically, which database you want (e.g. the
  account database for account 42), but don't want to tie your implementation to the details of finding that database.
  You can simply ask the resolver for accounts to find the right database for account 42.

  The `get-schema` method is useful when you need to know the schema, but don't care which instance of the database
  is used to obtain it (since a resolver should only resolve databases of a single type). Thus you can find meta-data
  about a database without needing to know specifially how to get to an individual instance of a real one.
  "
  (resolve-database
    ^Database [this req]
    "Resolve the database that is implied by the incoming web request.

    Parameters:
    * `this` The database resolver
    * `req` The web request that will contain the necessary information to resolve the database.

    Returns:
    A Database if resolution was successful, or nil is not found.
    ")
  (find-database
    ^Database [this args]
    "
    Find a database given some arbitrary arguments, which are defined by the specfic resolver.

    Parameters:
    * `this` The database resolver
    * `args` The arbitrary arguments required by a resolver.

    Returns:
    A Database if resolution was successful, or nil is not found.
    ")
  (get-schema
    [this]
    "
    Get the schema for the kind of databases that this resolver can resolve.

    Parameters:
    * `this` : The database resolver

    Returns:
    The schema configuration of the database, or nil if no database is resolvable.
    ")
  )
