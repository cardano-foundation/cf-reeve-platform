ALTER TYPE blockchain_publisher_blockchain_publish_status_type ADD VALUE 'RETRYING';
ALTER TYPE blockchain_publisher_blockchain_publish_status_type ADD VALUE 'ERROR';

ALTER TABLE blockchain_publisher_transaction
ADD COLUMN l1_publish_status_error_reason TEXT,
ADD COLUMN l1_publish_retry SMALLINT;

ALTER TABLE blockchain_publisher_report
ADD COLUMN l1_publish_status_error_reason TEXT,
ADD COLUMN l1_publish_retry SMALLINT;



