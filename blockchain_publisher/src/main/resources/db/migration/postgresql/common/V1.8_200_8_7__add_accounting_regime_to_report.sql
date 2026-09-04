-- Additive, nullable snapshot of the report template's accounting regime at time of publish.
-- Old published reports simply have NULL here.

ALTER TABLE blockchain_publisher_report_v2 ADD COLUMN accounting_regime VARCHAR(255);
