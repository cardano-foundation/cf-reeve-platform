-- 1) Add column to transaction table
ALTER TABLE accounting_core_transaction
    ADD COLUMN total_amount_lcy DECIMAL(30, 15);

ALTER TABLE accounting_core_transaction_aud
    ADD COLUMN total_amount_lcy DECIMAL(30, 15);

ALTER TABLE accounting_core_transaction
ADD COLUMN item_count INTEGER CHECK (item_count >= 0);

ALTER TABLE accounting_core_transaction_aud
ADD COLUMN item_count INTEGER CHECK (item_count >= 0);
-- 2) Update with calculated value
UPDATE accounting_core_transaction t
SET total_amount_lcy = CASE
    WHEN t.type = 'FxRevaluation' THEN ABS(
        COALESCE((SELECT SUM(i.amount_lcy)
                  FROM accounting_core_transaction_item i
                  WHERE i.transaction_id = t.transaction_id
                    AND i.operation_type = 'CREDIT'
                    AND i.status = 'OK'), 0)
      - COALESCE((SELECT SUM(i.amount_lcy)
                  FROM accounting_core_transaction_item i
                  WHERE i.transaction_id = t.transaction_id
                    AND i.operation_type = 'DEBIT'
                    AND i.status = 'OK'), 0)
    )

    WHEN t.type = 'Journal' THEN COALESCE((
        SELECT SUM(i.amount_lcy)
        FROM accounting_core_transaction_item i
        JOIN organisation o ON o.organisation_id = t.organisation_id
        WHERE i.transaction_id = t.transaction_id
          AND i.account_code_debit = o.dummy_account
          AND i.status = 'OK'
    ), 0)

    ELSE ABS(COALESCE((
        SELECT SUM(i.amount_lcy)
        FROM accounting_core_transaction_item i
        WHERE i.transaction_id = t.transaction_id 
        AND i.status = 'OK'
    ), 0))
END;

UPDATE accounting_core_transaction t
SET item_count = (SELECT COUNT(1)
    FROM accounting_core_transaction_item i
    WHERE i.transaction_id = t.transaction_id
    AND i.status = 'OK')