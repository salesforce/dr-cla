snyk monitor --file=build.sbt --org=salesforce --project-name="salesforce/dr-cla#$HEROKU_TEST_RUN_BRANCH"
snyk test --file=build.sbt && sbt test
