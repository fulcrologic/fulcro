#!/bin/bash

echo "You must be running the cards build for this server to be useful."
echo "As soon as you see the server start you can navigate to http://localhost:8085/cards.html"
lein run -m clojure.main -e "(require 'fulcro.democards.upload-server)" -e "(fulcro.democards.upload-server/go)"
