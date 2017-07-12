#!/bin/bash

echo "Preparing and starting the Fulcro Demos..."
lein cljsbuild once demos
echo "As soon as you see the web server start messages, you can navigate to http://localhost:8081/demos.html"
lein run -m clojure.main -e "(require 'user)" -e "(user/run-demo-server)"
