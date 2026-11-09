-- Local simulated-money development credentials, never production credentials.
CREATE USER payments WITH PASSWORD 'payments-local';
CREATE DATABASE payments OWNER payments;
REVOKE ALL ON DATABASE payments FROM PUBLIC;
CREATE USER ledger WITH PASSWORD 'ledger-local';
CREATE DATABASE ledger OWNER ledger;
REVOKE ALL ON DATABASE ledger FROM PUBLIC;
