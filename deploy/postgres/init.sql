-- One database and one login role per service; each role owns only its database
-- (specs/00-engineering-baseline.md §9, §13). Run once by the postgres image on an empty data volume.
-- The passwords are for the local compose stack only (00 §12.2).

CREATE ROLE api LOGIN PASSWORD 'api-local';
CREATE DATABASE api OWNER api;
REVOKE ALL ON DATABASE api FROM PUBLIC;

CREATE ROLE payments LOGIN PASSWORD 'payments-local';
CREATE DATABASE payments OWNER payments;
REVOKE ALL ON DATABASE payments FROM PUBLIC;

CREATE ROLE ledger LOGIN PASSWORD 'ledger-local';
CREATE DATABASE ledger OWNER ledger;
REVOKE ALL ON DATABASE ledger FROM PUBLIC;

CREATE ROLE vop LOGIN PASSWORD 'vop-local';
CREATE DATABASE vop OWNER vop;
REVOKE ALL ON DATABASE vop FROM PUBLIC;

CREATE ROLE risk LOGIN PASSWORD 'risk-local';
CREATE DATABASE risk OWNER risk;
REVOKE ALL ON DATABASE risk FROM PUBLIC;

CREATE ROLE csm LOGIN PASSWORD 'csm-local';
CREATE DATABASE csm OWNER csm;
REVOKE ALL ON DATABASE csm FROM PUBLIC;

CREATE ROLE notification LOGIN PASSWORD 'notification-local';
CREATE DATABASE notification OWNER notification;
REVOKE ALL ON DATABASE notification FROM PUBLIC;

-- the default databases are not for services either
REVOKE ALL ON DATABASE postgres FROM PUBLIC;
REVOKE ALL ON DATABASE template1 FROM PUBLIC;
