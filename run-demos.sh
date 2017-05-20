#!/bin/bash

echo "Preparing and starting the Untangled Demos..."
lein cljsbuild once demos
echo "As soon as you see the web server start messages, you can navigate to http://localhost:8081/demos.html"
lein run -m clojure.main script/demos.clj 
