#!/bin/bash

export LEIN_FAST_TRAMPOLINE=y

if [ -z "$*" ]; then
  echo "Usage: $0 build-id ..."
  echo -n "Valid build ids are: "
  grep :id project.clj | sed -e 's/^.*"\([^"][^"]*\)".*/\1/' | xargs -n20 echo
  exit 1
fi

lein run -m clojure.main -e "(require 'user)" -e "(user/start-figwheel '[$*])"
