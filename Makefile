tests:
	npm install
	lein doo chrome automated-tests once

guide:
	rm -rf docs/js/*
	lein with-profile devguide cljsbuild once devguide-live
