```
docker-compose down -v && docker-compose up -d

k6 run --out experimental-prometheus-rw \     
-e K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
k6/scripts/phase1-read-test.js
```
