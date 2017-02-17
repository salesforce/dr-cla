Salesforce CLA Tools
====================

This is a set of tools for dealing with Contributor License Agreements for Open Source Salesforce projects.

The tools provided are:
- Pull Request CLA Verifier
- GitHub Org Audit

This application is built with:
- Play Framework 2.4
- Scala
- Postgres
- Heroku Connect
- Reactive I/O (Non-Blocking)


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

1. Setup a GitHub Integration testing user.
1. Setup a new Integration in your Integration testing user's account at https://github.com/settings/integrations with the following settings:
    - *Webhook URL* = `https://YOUR_NGROK_ID.ngrok.io/webhook-integration`
    - *Repository administration* = `Read-only`
    - *Commit statuses* = `Read & Write`
    - *Issues* = `Read & Write`
    - *Pull request* = `Read & Write`
    - Select *Pull request*
    - *Repository contents* = `Read-only`
    - *Organization members* = `Read-only`

    It is not required, but if you set the GitHub Integration Secret Token, then set the `GITHUB_INTEGRATION_SECRET_TOKEN` env var accordingly.

1. Generate and save a new Private key for the new Integration, then set the `GITHUB_INTEGRATION_PRIVATE_KEY` env var accordingly:

        export GITHUB_INTEGRATION_PRIVATE_KEY=$(cat ~/somewhere/salesforce-cla-test.2017-02-07.private-key.pem)

1. Your new Integration will have a numeric id, set the `GITHUB_INTEGRATION_ID` env var accordingly.
1. Create a personal access token at https://github.com/settings/tokens with the following permissions: `admin:org, admin:org_hook, admin:public_key, admin:repo_hook, delete_repo, repo, user` then set the `GITHUB_TOKEN` env var accordingly.
1. Create a new Developer Application on GitHub:
    a. Register a new Developer Application in your org: `https://github.com/organizations/YOUR_ORG/settings/applications/new`
    a. Your callback URL will use your ngrok host: `http://SOMETHING.ngrok.com/_github_oauth_callback`
    a. Set the `GITHUB_CLIENT_ID` and `GITHUB_CLIENT_SECRET` env vars accordingly

Run the Web App
---------------

1. Start the web app:

        $ ./activator ~run

1. Authenticate to GitHub with your test user
1. Open the audit page: `https://SOMETHING.ngrok.com/audit`
1. You should see a list of organizations and repos and the CLA Webhook status for each
1. Add the CLA Webhook to your testing organization
1. In GitHub edit the `README` file the testing repo and submit a pull request
1. This will make a webhook request to your local application and validate the CLA status of the submitter
1. You can see the webhooks, their deliveries, and redeliver the webhooks at: `https://github.com/organizations/YOUR_ORG/settings/hooks`
1. If you made the pull request as your testing user, the Pull Request status should be **mergable** because the user is considered an internal contributor (because it has access to the repo)
1. If you make another pull request as a different user (using a fork) then the Pull Request status will be **unmergable** because the user hasn't signed the CLA and instructions for how to do so will be posted on the PR


Run the Tests
-------------

1. You will need two additional GitHub testing users.  For each, create a personal access token at https://github.com/settings/tokens with the following permissions: `admin:org, admin:org_hook, admin:public_key, admin:repo_hook, delete_repo, repo, user`

1. For user one, create a new testing organization (because this can't be done via the API).  Add the integration user as a member of this org.

1. For user one, create second new testing organization.  Add the second user as a private member of this org.  Install your integration into this org.

1. For user one, install the integration.

1. Set the `GITHUB_TEST_TOKEN1`, `GITHUB_TEST_ORG1`, `GITHUB_TEST_ORG2`, and `GITHUB_TEST_TOKEN2` environment variables.

1. Run all of the tests continuously:

        $ ./activator ~test

1 Run just the `GitHubSpec` tests continuously:

        $ ./activator ~testOnly utils.GitHubSpec
