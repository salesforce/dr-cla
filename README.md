Salesforce CLA Tools
====================

This is a set of tools for dealing with Contributor License Agreements for Open Source Salesforce projects.

The tools provided are:
- Pull Request CLA Verifier
- GitHub Org Audit

This application is built with:
- Play Framework 2.5
- Scala
- Postgres
- Heroku Connect
- Reactive I/O (Non-Blocking)


How it Works
------------

When someone sends a Pull Request to a project on GitHub, a Webhook sends details to this app.  The authors of the commits in the PR are checked to see if they are collaborators on the repo.  If not, the app checks if they have signed CLAs.  If there are missing CLAs then the status of the PR is set to failed.  Otherwise it is set to success.  Also if there are missing CLAs then a comment is posted on the PR asking the contributors to sign the CLA.  Once a contributor signs a CLA, all of the open PRs are revalidated.


[![Deploy on Heroku](https://www.herokucdn.com/deploy/button.svg)](https://heroku.com/deploy)


Local Dev Setup
---------------

1. Install Java 8
1. Install Postgres
1. Install ngrok
1. Start ngrok: `ngrok http 9000`
1. Create local Postgres databases:

        $ psql
        # CREATE ROLE salesforcecla LOGIN password 'password';
        # CREATE DATABASE salesforcecla ENCODING 'UTF8' OWNER salesforcecla;
        # CREATE DATABASE "salesforcecla-test" ENCODING 'UTF8' OWNER salesforcecla;

1. [Setup a new GitHub App](https://github.com/settings/apps) with the following settings:
    - *Webhook URL* = `https://YOUR_NGROK_ID.ngrok.io/webhook-integration`
    - *Repository administration* = `Read-only`
    - *Commit statuses* = `Read & Write`
    - *Issues* = `Read & Write`
    - *Pull requests* = `Read & Write`
    - *Repository contents* = `Read-only`
    - *Organization members* = `Read-only`
    - Under *Subscribe to events* select *Pull request*

    It is not required, but if you set the GitHub Integration Secret Token, then set the `GITHUB_INTEGRATION_SECRET_TOKEN` env var accordingly.

1. Generate and save a new Private key for the new Integration, then set the `GITHUB_INTEGRATION_PRIVATE_KEY` env var accordingly, like:

        export GITHUB_INTEGRATION_PRIVATE_KEY=$(cat ~/somewhere/your-integration.2017-02-07.private-key.pem)

1. Your new GitHub App will have a numeric id, set the `GITHUB_INTEGRATION_ID` env var accordingly.
1. Your new GitHub App will have a slug / URL friendly name, set the `GITHUB_INTEGRATION_SLUG` env var accordingly.
1. Create a new Developer Application on GitHub:
    a. Register a new Developer Application in your org: `https://github.com/organizations/YOUR_ORG/settings/applications/new`
    a. Your callback URL will use your ngrok host: `http://SOMETHING.ngrok.com/_github_oauth_callback`
    a. Set the `GITHUB_CLIENT_ID` and `GITHUB_CLIENT_SECRET` env vars accordingly

Run the Web App
---------------

1. Start the web app:

        $ ./sbt ~run

1. Authenticate to GitHub with your test user
1. Open the audit page: `https://SOMETHING.ngrok.com/audit`
1. You should see a list of organizations which have the GitHub App installed and which you are an admin of
1. In GitHub edit the `README` file the testing repo and submit a pull request
1. This will make a webhook request to your local application and validate the CLA status of the submitter
1. You can see event deliveries in the Developer Settings for your GitHub App
1. If you make a PR with a testing user that is not part of the org, you should see the PR validation failure and be able to sign the CLA


Run the Tests
-------------

1. You will need two GitHub testing users.  For each, [create a personal access token](https://github.com/settings/tokens) with the following permissions: `admin:org, admin:org_hook, admin:public_key, admin:repo_hook, delete_repo, repo, user`

1. For user one, create a new testing organization (because this can't be done via the API).  Add the second user as a member of this org.

1. For user one, install the GitHub App into the user's account and into the testing org.

1. Set the `GITHUB_TEST_TOKEN1`, `GITHUB_TEST_ORG`, and `GITHUB_TEST_TOKEN2` env vars.

1. Run all of the tests continuously:

        $ ./sbt ~test

1 Run just the `GitHubSpec` tests continuously:

        $ ./sbt ~testOnly utils.GitHubSpec
