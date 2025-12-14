-- MySQL 초기화 스크립트
-- 테스트 환경에서 필요한 추가 설정

-- 성능 모니터링을 위한 설정
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 1;

-- 연결 관련 정보 확인용
SELECT 'MySQL initialized for stress testing' AS message;
SHOW VARIABLES LIKE 'max_connections';
