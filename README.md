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
    ```
    $ psql
    # CREATE ROLE salesforcecla LOGIN password 'password';
    # CREATE DATABASE salesforcecla ENCODING 'UTF8' OWNER salesforcecla;
    # CREATE DATABASE "salesforcecla-test" ENCODING 'UTF8' OWNER salesforcecla;
    ```
1. Create a new GitHub user for testing
1. As your testing user, create a new GitHub organization and a testing repo in that organization
1. In the testing repo, click the `README` link to initialize the repo and create a testing file
1. Create a new Developer Application on GitHub:
    a. Register a new Developer Application in your org: `https://github.com/organizations/YOUR_ORG/settings/applications/new`
    a. Your callback URL will use your ngrok host: `http://SOMETHING.ngrok.com/_github_oauth_callback`
    a. Take note of your OAUTH Client ID and Secret
1. Create a new GitHub Personal Access Token:
    a. Create a new Personal Access Token: [https://github.com/settings/tokens](https://github.com/settings/tokens)
    a. Enable the following scopes: `public_repo` `repo:status` `read:org` `repo_deployment` `admin:org_hook`
    a. Take note of your personal access token


Run the Web App
---------------

1. Start the web app:
    ```
    $ GITHUB_CLIENT_ID=<YOUR OAUTH CLIENT ID> GITHUB_CLIENT_SECRET=<YOUR OAUTH CLIENT SECRET> GITHUB_TOKEN=<YOUR PERSONAL ACCESS TOKEN> ./activator ~run
    ```
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

- Run all of the tests continuously:
    ```
    $ GITHUB_CLIENT_ID=<YOUR OAUTH CLIENT ID> GITHUB_CLIENT_SECRET=<YOUR OAUTH CLIENT SECRET> GITHUB_TOKEN=<YOUR PERSONAL ACCESS TOKEN> ./activator ~test
    ```
- Run just the `GitHubSpec` tests continuously:
    ```
    $ GITHUB_CLIENT_ID=<YOUR OAUTH CLIENT ID> GITHUB_CLIENT_SECRET=<YOUR OAUTH CLIENT SECRET> GITHUB_TOKEN=<YOUR PERSONAL ACCESS TOKEN> ./activator ~testOnly utils.GitHubSpec
    ```