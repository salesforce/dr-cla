CREATE SCHEMA IF NOT EXISTS salesforce;

DROP TABLE IF EXISTS salesforce.CLA_Signature__c;
DROP TABLE IF EXISTS salesforce.Contact;

CREATE TABLE salesforce.Contact (
  id SERIAL PRIMARY KEY,
  sfid VARCHAR NOT NULL UNIQUE,
  firstname VARCHAR NOT NULL,
  lastname VARCHAR NOT NULL,
  email VARCHAR NOT NULL
);

CREATE TABLE salesforce.CLA_Signature__c (
  id SERIAL PRIMARY KEY,
  sfid VARCHAR NOT NULL UNIQUE,
  contact__c VARCHAR NOT NULL REFERENCES salesforce.Contact (sfid),
  github_id__c VARCHAR NOT NULL,
  signed_on__c TIMESTAMP WITH TIME ZONE NOT NULL,
  cla_version__c VARCHAR NOT NULL
);