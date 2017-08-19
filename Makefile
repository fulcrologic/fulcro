tests:
	npm install
	lein doo chrome automated-tests once

guide:
	rm -rf docs/js/*
	lein with-profile production cljsbuild once devguide-live
