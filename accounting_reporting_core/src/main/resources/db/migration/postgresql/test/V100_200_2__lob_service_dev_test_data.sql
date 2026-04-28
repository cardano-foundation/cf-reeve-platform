-- Transaction 1
INSERT INTO accounting_core_transaction_batch
VALUES ('DUMMY_BATCH1', 'FINISHED', 3, 3, 0, 1, 0, 0, 2,
        NULL, NULL, NULL, '75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94', 1, '{}', '2010-01-01',
        '2024-05-01', '2021-06-01', '2024-06-01', 'system', 'system', '2020-08-17 18:25:00.060749',
        '2020-08-17 18:25:00.060749', 'NETSUITE');

INSERT INTO accounting_core_transaction
VALUES ('VALIDATED_TRANSACTION', 'VendorBill',
        'DUMMY_BATCH1', 'INVALID', '2023-12-06', '2023-12', 'VENDBILL150',
        '75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94', 'Cardano Foundation', 'CH', 'CHE-184477354',
        'ISO_4217:CHF', NULL, NULL, NULL, NULL, NULL, 'VALIDATED', false, false, 'NOT_DISPATCHED', null, null, 'NOK',
        'system', 'system', '2024-11-11 13:15:28.10435', '2024-11-11 13:15:28.10435', 'NETSUITE', 0.0, 0, NULL);
INSERT INTO accounting_core_transaction_batch_assoc
VALUES ('DUMMY_BATCH1',
        'VALIDATED_TRANSACTION', 'system', 'system',
        '2024-06-13 14:09:12.050311', '2024-06-13 14:09:12.050311');

-- Transaction 2
INSERT INTO accounting_core_transaction_batch
VALUES ('DUMMY_BATCH2-TestRejection', 'FINISHED', 1, 1, 0, 1, 0, 0, 0,
        NULL, NULL, NULL, '75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94', 1, '{}', '2010-01-01',
        '2024-05-01', '2021-06-01', '2024-06-01', 'system', 'system', '2024-07-17 18:25:00.060749',
        '2024-08-16 18:25:00.060749', 'NETSUITE');
INSERT INTO accounting_core_transaction
VALUES ('Pending_by_rejection', 'CardCharge',
        'DUMMY_BATCH2-TestRejection', 'PENDING', '2023-07-04', '2023-07', 'CARDCHRG1593',
        '75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94', 'Cardano Foundation', 'CH', 'CHE-184477354',
        'ISO_4217:CHF', NULL, NULL, NULL, NULL, NULL, 'VALIDATED', false, false, 'NOT_DISPATCHED', null, null, 'NOK',
        'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'NETSUITE', 0.0, 0, NULL);
INSERT INTO accounting_core_transaction_batch_assoc
VALUES ('DUMMY_BATCH2-TestRejection',
        'Pending_by_rejection', 'system', 'system',
        '2024-06-13 14:09:12.050311', '2024-06-13 14:09:12.050311');

-- Transaction 3
INSERT INTO accounting_core_transaction_batch
VALUES ('DUMMY_BATCH3-Invalid', 'FINISHED', 1, 1, 0, 0, 0, 0, 1,
        NULL, NULL, NULL, '75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94', 1,
        '{"CARDCH565", "CARDHY777", "CARDCHRG159", "VENDBIL119"}', '2010-01-01', '2024-05-01', '2021-06-01',
        '2024-06-01', 'system', 'system', '2024-01-15 18:25:00.060749', '2024-01-15 18:25:00.060749', 'NETSUITE');
INSERT INTO accounting_core_transaction
VALUES ('InvalidTx', 'CardCharge',
        'DUMMY_BATCH3-Invalid', 'INVALID', '2023-07-04', '2023-07', 'CARDCHRG159',
        '75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94', 'Cardano Foundation', 'CH', 'CHE-184477354',
        'ISO_4217:CHF', NULL, NULL, NULL, NULL, null, 'FAILED', false, false, 'NOT_DISPATCHED', null, null, 'NOK',
        'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'NETSUITE', 0.0, 0, NULL);
INSERT INTO accounting_core_transaction_batch_assoc
VALUES ('DUMMY_BATCH3-Invalid',
        'InvalidTx', 'system', 'system',
        '2024-06-13 14:09:12.050311', '2024-06-13 14:09:12.050311');

-- Transaction 4
INSERT INTO accounting_core_transaction_batch
VALUES ('DUMMY_BATCH4-Approve', 'FINISHED', 3, 3, 3, 0, 0, 0, 0,
        NULL, NULL, NULL, '75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94', 1, '{}', '2010-01-01',
        '2024-05-01', '2021-06-01', '2024-06-01', 'system', 'system', '2024-08-16 18:25:00.060749',
        '2024-08-16 18:25:00.060749', 'NETSUITE');

INSERT INTO accounting_core_transaction
VALUES ('ApproveTx', 'CardCharge',
        'DUMMY_BATCH4-Approve', 'APPROVE', '2023-07-04', '2023-07', 'CARDCHRG159a',
        '75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94', 'Cardano Foundation', 'CH', 'CHE-184477354',
        'ISO_4217:CHF', NULL, NULL, NULL, NULL, null, 'VALIDATED', false, false, 'NOT_DISPATCHED', null, null, 'NOK',
        'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'NETSUITE', 0.0, 0, NULL);

-- Transaction 5
INSERT INTO accounting_core_transaction_batch
VALUES ('DUMMY_BATCH5-Publish', 'FINISHED', 3, 3, 0, 0, 3, 0, 0,
        NULL, NULL, NULL, '75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94', 1, '{}', '2010-01-01',
        '2024-05-01', '2021-06-01', '2024-06-01', 'system', 'system', '2024-08-18 18:25:00.060749',
        '2024-08-18 18:25:00.060749', 'NETSUITE');
INSERT INTO accounting_core_transaction
VALUES ('PublishTx', 'CardCharge',
        'DUMMY_BATCH5-Publish', 'PUBLISH', '2022-12-31', '2022-12', 'JOURNAL28',
        '75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94', 'Cardano Foundation', 'CH', 'CHE-184477354',
        'ISO_4217:CHF', NULL, NULL, NULL, NULL, NULL, 'VALIDATED', true, false, 'NOT_DISPATCHED', null, null, 'NOK',
        'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'NETSUITE', 0.0, 0, NULL);

-- Transaction 6
INSERT INTO accounting_core_transaction_batch
VALUES ('DUMMY_BATCH6-Published', 'FINISHED', 3, 3, 0, 0, 2, 1, 0,
        NULL, NULL, NULL, '75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94', 1, '{}', '2010-01-01',
        '2024-05-01', '2021-06-01', '2024-06-01', 'system', 'system', '2024-07-17 18:25:00.060749',
        '2024-07-17 18:25:00.060749', 'NETSUITE');

INSERT INTO accounting_core_transaction
VALUES ('PublishedTx', 'CardCharge',
        'DUMMY_BATCH6-Published', 'PUBLISHED', '2023-07-04', '2023-07', 'CARDCHaRG159',
        '75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94', 'Cardano Foundation', 'CH', 'CHE-184477354',
        'ISO_4217:CHF', NULL, NULL, NULL, NULL, null, 'VALIDATED', true, true, 'MARK_DISPATCH', null, null, 'OK',
        'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'NETSUITE', 0.0, 0, NULL);
