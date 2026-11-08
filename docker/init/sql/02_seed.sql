-- 初始化一条空白的共享盘面简报。
USE `quant_cloud`;

INSERT INTO `market_briefing` (`id`) VALUES (1)
ON DUPLICATE KEY UPDATE `id` = 1;
