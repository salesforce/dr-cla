CREATE SCHEMA IF NOT EXISTS salesforce;

DROP TABLE IF EXISTS salesforce.CLA_Signature__c;
DROP TABLE IF EXISTS salesforce.Contact;

CREATE TABLE salesforce.Contact (
  id SERIAL PRIMARY KEY,
  firstname VARCHAR,
  lastname VARCHAR NOT NULL,
  email VARCHAR NOT NULL,
  sf_cla__github_id__c VARCHAR UNIQUE
);

CREATE TABLE salesforce.sf_cla__CLA_Signature__c (
  id SERIAL PRIMARY KEY,
  sf_cla__contact__r__sf_cla__github_id__c VARCHAR NOT NULL REFERENCES salesforce.Contact (sf_cla__github_id__c),
  sf_cla__signed_on__c TIMESTAMP WITHOUT TIME ZONE NOT NULL,
  sf_cla__cla_version__c VARCHAR NOT NULL
);
