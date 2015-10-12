CREATE SCHEMA IF NOT EXISTS salesforce;

DROP TABLE IF EXISTS salesforce.CLA_Signature__c;
DROP TABLE IF EXISTS salesforce.Contact;

CREATE TABLE salesforce.Contact (
  id VARCHAR PRIMARY KEY,
  firstname VARCHAR NOT NULL,
  lastname VARCHAR NOT NULL,
  email VARCHAR NOT NULL
);

CREATE TABLE salesforce.CLA_Signature__c (
  id VARCHAR PRIMARY KEY,
  contact VARCHAR NOT NULL REFERENCES salesforce.Contact (id),
  github_id VARCHAR NOT NULL,
  signed_on DATE NOT NULL,
  cla_version VARCHAR NOT NULL
);