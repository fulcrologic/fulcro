#!/bin/bash

echo "You must be running the devguide for this server to be useful."
echo "As soon as you see the server start you can navigate to http://localhost:8085/guide.html"
lein run -m clojure.main script/file-uploads.clj
