-- Reports previously had no dispatch locking window at all: give blockchain_publisher_report_v2 the same
-- locked_at claim marker the transaction and spending event tables already have.
ALTER TABLE blockchain_publisher_report_v2 ADD COLUMN locked_at TIMESTAMP WITHOUT TIME ZONE;
