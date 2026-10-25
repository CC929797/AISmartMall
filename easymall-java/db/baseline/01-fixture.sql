-- Phase 0 synthetic fixture. No records below come from a real person or shop.
-- Import only after 00-schema.sql, before starting any application, into an empty
-- isolated easymall_test database. This file never updates or replaces rows.
-- Fake login: baseline@example.invalid / Baseline123 (current MD5 protocol).
-- All INSERT statements are gated; the executor must require fixture_allowed=1
-- and verify the final counts. Do not use mysql --force or remove the guard.

SET NAMES utf8mb4;
START TRANSACTION;
SET @baseline_fixture_allowed =
    DATABASE() = 'easymall_test'
    AND NOT EXISTS (SELECT 1 FROM agent_message)
    AND NOT EXISTS (SELECT 1 FROM order_comment)
    AND NOT EXISTS (SELECT 1 FROM order_info)
    AND NOT EXISTS (SELECT 1 FROM order_item)
    AND NOT EXISTS (SELECT 1 FROM order_logistics_info)
    AND NOT EXISTS (SELECT 1 FROM order_logistics_info_record)
    AND NOT EXISTS (SELECT 1 FROM product_cart)
    AND NOT EXISTS (SELECT 1 FROM product_info)
    AND NOT EXISTS (SELECT 1 FROM product_property_value)
    AND NOT EXISTS (SELECT 1 FROM product_sku)
    AND NOT EXISTS (SELECT 1 FROM rag_question)
    AND NOT EXISTS (SELECT 1 FROM statistics_info)
    AND NOT EXISTS (SELECT 1 FROM sys_category)
    AND NOT EXISTS (SELECT 1 FROM sys_product_property)
    AND NOT EXISTS (SELECT 1 FROM user_address)
    AND NOT EXISTS (SELECT 1 FROM user_info);
SELECT @baseline_fixture_allowed AS fixture_allowed;

INSERT INTO user_info
    (user_id, nick_name, avatar, email, password, sex, join_time, status, api_key)
SELECT 'BASE000001', 'Baseline fixture', '', 'baseline@example.invalid',
       MD5('Baseline123'), 2, '2026-10-25 00:00:00', 1, NULL
WHERE @baseline_fixture_allowed;

INSERT INTO sys_category (category_id, category_name, p_category_id, sort)
SELECT 'B0001', 'Baseline electronics', '0', 1 WHERE @baseline_fixture_allowed
UNION ALL
SELECT 'B0002', 'Baseline laptops', 'B0001', 1 WHERE @baseline_fixture_allowed;

INSERT INTO sys_product_property
    (property_id, property_name, p_category_id, category_id, property_sort, cover_type)
SELECT 'BASEPROP01', 'Configuration', 'B0001', 'B0002', 1, 0
WHERE @baseline_fixture_allowed;

INSERT INTO product_info
    (product_id, product_name, product_desc, cover, create_time,
     category_id, p_category_id, status, min_price, max_price, total_sale, commend_type)
SELECT 'BASELINEPROD001', 'Baseline Laptop',
       'Synthetic phase 0 laptop. Not offered for sale or shipping.',
       'baseline/fixture-product.png', '2026-10-25 00:00:00',
       'B0002', 'B0001', 1, 3999.00, 3999.00, 0, 0
WHERE @baseline_fixture_allowed;

INSERT INTO product_property_value
    (product_id, property_id, property_name, property_sort, cover_type,
     property_value_id, property_cover, property_value, property_remark, sort)
SELECT 'BASELINEPROD001', 'BASEPROP01', 'Configuration', 1, 0,
       'BASEVALUE001', NULL, '16GB / 512GB', 'Synthetic test specification', 1
WHERE @baseline_fixture_allowed;

INSERT INTO product_sku
    (product_id, property_value_id_hash, property_value_ids, price, stock, sort)
SELECT 'BASELINEPROD001', MD5('BASEVALUE001'), 'BASEVALUE001', 3999.00, 10, 1
WHERE @baseline_fixture_allowed;

INSERT INTO user_address
    (address_id, user_id, address, addressee, phone, default_type)
SELECT 'BASEADDR000001', 'BASE000001', 'Synthetic test address; do not ship',
       'Baseline fixture', '00000000000', 1
WHERE @baseline_fixture_allowed;

COMMIT;
SELECT @baseline_fixture_allowed AS fixture_allowed,
       (SELECT COUNT(*) FROM user_info) AS user_count,
       (SELECT COUNT(*) FROM sys_category) AS category_count,
       (SELECT COUNT(*) FROM product_info) AS product_count,
       (SELECT COUNT(*) FROM product_sku) AS sku_count,
       (SELECT COUNT(*) FROM user_address) AS address_count;
