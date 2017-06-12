#!/bin/bash

echo "Preparing and starting the Untangled Develpers Guide..."
echo "As soon as you see the compilation complete you can navigate to http://localhost:8080/guide.html"
export JVM_OPTS='-Ddevguide'
lein run -m clojure.main script/figwheel.clj
