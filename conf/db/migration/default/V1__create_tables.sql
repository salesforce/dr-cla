CREATE SCHEMA IF NOT EXISTS salesforce;

DROP TABLE IF EXISTS salesforce.CLA_Signature__c;
DROP TABLE IF EXISTS salesforce.Contact;

CREATE TABLE salesforce.Contact (
  id SERIAL PRIMARY KEY,
  firstname VARCHAR NOT NULL,
  lastname VARCHAR NOT NULL,
  email VARCHAR NOT NULL,
  github_id__c VARCHAR NOT NULL UNIQUE
);

CREATE TABLE salesforce.CLA_Signature__c (
  id SERIAL PRIMARY KEY,
  contact__r__github_id__c VARCHAR NOT NULL REFERENCES salesforce.Contact (github_id__c),
  signed_on__c TIMESTAMP NOT NULL,
  cla_version__c VARCHAR NOT NULL
);