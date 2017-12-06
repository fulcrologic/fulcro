tests:
	npm install
	lein doo chrome automated-tests once

guide:
	rm -rf docs/js/[a-fh-z]* docs/js/goog docs/js/garden
	lein with-profile production cljsbuild once devguide-live
