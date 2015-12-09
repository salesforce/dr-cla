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

To work on this app locally:
1. Create a local Postgres database:
    ```sh
        $ psql
        
        # CREATE ROLE salesforcecla LOGIN password 'password';
        
        # CREATE DATABASE salesforcecla ENCODING 'UTF8' OWNER salesforcecla;
    ```
2. Start the web app:
    ```
        $ ./activator ~run
    ```

