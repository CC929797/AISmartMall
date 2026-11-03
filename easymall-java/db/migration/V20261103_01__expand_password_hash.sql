-- 步骤04：先选择正确的数据库，再执行本脚本；应用不会自动执行结构迁移。
-- 适用：MySQL 8，原字段为 user_info.password varchar(50) NOT NULL。
-- 仅扩容并使密码编码区分大小写，不重置账号，不批量转换旧密码。
ALTER TABLE user_info
    MODIFY COLUMN password VARCHAR(255)
    CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '密码哈希';

-- 仅返回结构，不输出密码内容。
SELECT TABLE_SCHEMA, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLLATION_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_info' AND COLUMN_NAME = 'password';
