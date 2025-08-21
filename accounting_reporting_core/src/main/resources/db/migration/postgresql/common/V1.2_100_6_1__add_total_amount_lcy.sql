-- 1) Add column to transaction table
ALTER TABLE accounting_core_transaction
    ADD COLUMN total_amount_lcy DECIMAL(38, 10);

ALTER TABLE accounting_core_transaction_aud
    ADD COLUMN total_amount_lcy DECIMAL(38, 10);
-- 2) Update with calculated value
UPDATE accounting_core_transaction t
SET total_amount_lcy = CASE
    -- FxRevaluation transactions: absolute difference between CREDIT and DEBIT
    WHEN t.type = 'FxRevaluation' THEN ABS(
        COALESCE((
            SELECT SUM(i.amount_lcy)
            FROM accounting_core_transaction_item i
            WHERE i.transaction_id = t.transaction_id
              AND i.operation_type = 'CREDIT'
        ), 0)
      - COALESCE((
            SELECT SUM(i.amount_lcy)
            FROM accounting_core_transaction_item i
            WHERE i.transaction_id = t.transaction_id
              AND i.operation_type = 'DEBIT'
        ), 0)
    )


    WHEN t.type = 'Journal' THEN COALESCE((
        SELECT SUM(i.amount_lcy)
        FROM accounting_core_transaction_item i
        JOIN organisation o ON o.organisation_id = t.organisation_id
        WHERE i.transaction_id = t.transaction_id
          AND i.account_code_debit = o.dummy_account
    ), 0)

    -- Default: sum of all items
    ELSE COALESCE((
        SELECT SUM(i.amount_lcy)
        FROM accounting_core_transaction_item i
        WHERE i.transaction_id = t.transaction_id
    ), 0)
END;
