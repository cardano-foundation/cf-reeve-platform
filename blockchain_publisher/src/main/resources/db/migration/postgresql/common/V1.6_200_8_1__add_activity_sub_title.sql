-- Spending event publishing tables (blockchain_publisher module).
-- Mirrors the spending entities in
-- org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.
ALTER TABLE blockchain_publisher_spending_event
   ADD COLUMN activity_sub_title VARCHAR(255);
ALTER TABLE blockchain_publisher_spending_event_aud
    ADD COLUMN activity_sub_title VARCHAR(255);