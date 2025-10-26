# Stock Test Application

Spring Boot 기반 주식 테스트 애플리케이션

## 기술 스택

- **Language**: Kotlin 1.9.25
- **Framework**: Spring Boot 3.5.6
- **Database**: MySQL (AWS Aurora)
- **Cloud**: AWS (Parameter Store, RDS)
- **Build Tool**: Gradle

## 주요 기능

- AWS Parameter Store를 통한 설정 관리
- AWS Aurora (MySQL) 데이터베이스 연동
- Spring Data JPA를 통한 데이터 접근
- Spring Boot Actuator 헬스체크
- **JSON 역직렬화 성능 테스트**: 의류 상품 메타데이터 조회 API

## 로컬 개발 환경 설정

### 1. 사전 요구사항

- Java 21
- AWS CLI 설치 및 설정
- Session Manager 플러그인 설치

```bash
# AWS CLI 설치 확인
aws --version

# Session Manager 플러그인 설치 (macOS)
brew install --cask session-manager-plugin
```

### 2. AWS 자격증명 설정

```bash
aws configure
```

다음 정보를 입력하세요:
- AWS Access Key ID
- AWS Secret Access Key
- Default region name: `ap-northeast-2`

### 3. SSM 포트 포워딩 설정

프라이빗 서브넷의 RDS에 접근하기 위해 SSM Session Manager를 통한 포트 포워딩이 필요합니다.

```bash
# 1. 설정 파일 생성
cp ssm-tunnel-config.sh.example ssm-tunnel-config.sh

# 2. ssm-tunnel-config.sh 파일을 열어 실제 값 입력
# - EC2_INSTANCE_ID: VPC 내부의 EC2 인스턴스 ID
# - RDS_ENDPOINT: RDS 엔드포인트 주소

# 3. 포트 포워딩 시작
./ssm-tunnel.sh
```

포트 포워딩이 시작되면 **터미널을 열어둔 채로** 다른 터미널에서 애플리케이션을 실행하세요.

### 4. 애플리케이션 실행

```bash
# 터널이 열린 상태에서 새 터미널 열고 실행
./gradlew bootRun
```

애플리케이션이 시작되면 ParameterStore에서 가져온 설정값이 로그에 출력됩니다:

```
================================================================================
ParameterStore 값 확인
my-first-database-host: database-1.xxxxx.ap-northeast-2.rds.amazonaws.com
my-first-database-password: Wo****************GL
================================================================================
```

### 5. 로컬 프로파일 사용 (선택사항)

SSM 터널 대신 로컬 MySQL을 사용하려면:

```bash
# 1. 로컬 설정 파일 생성
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml

# 2. application-local.yml 파일 수정

# 3. local 프로파일로 실행
./gradlew bootRun --args='--spring.profiles.active=local'
```

## AWS Parameter Store 설정

다음 파라미터들이 AWS Parameter Store에 등록되어 있어야 합니다:

| 파라미터 이름 | 타입 | 설명 |
|--------------|------|------|
| `/my-first-database-host` | String | RDS 엔드포인트 주소 |
| `/my-first-database-password` | SecureString | 데이터베이스 비밀번호 |

### 파라미터 등록 예시

```bash
# RDS 호스트 등록
aws ssm put-parameter \
  --name "/my-first-database-host" \
  --value "your-rds-endpoint.ap-northeast-2.rds.amazonaws.com" \
  --type "String"

# RDS 비밀번호 등록 (암호화)
aws ssm put-parameter \
  --name "/my-first-database-password" \
  --value "your-secure-password" \
  --type "SecureString"
```

## IAM 권한 요구사항

### 로컬 개발자 권한

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ssm:GetParameter",
        "ssm:GetParameters",
        "ssm:GetParametersByPath"
      ],
      "Resource": "arn:aws:ssm:ap-northeast-2:*:parameter/*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "ssm:StartSession"
      ],
      "Resource": [
        "arn:aws:ec2:ap-northeast-2:*:instance/*",
        "arn:aws:ssm:*:*:document/AWS-StartPortForwardingSessionToRemoteHost"
      ]
    }
  ]
}
```

### EC2 인스턴스 역할 (Bastion)

EC2 인스턴스에 `AmazonSSMManagedInstanceCore` 정책 연결 필요

## JSON 역직렬화 성능 테스트

이 애플리케이션은 복잡한 JSON 데이터의 역직렬화 성능을 측정하기 위해 설계되었습니다.

### 데이터 구조

**Product 엔티티**
```
products 테이블
├── id (PK)
├── name (상품명)
├── category (카테고리)
├── price (가격)
├── brand (브랜드)
├── product_code (상품코드, unique)
├── metadata_json (JSON 컬럼, 30-50KB)
├── created_at
└── updated_at
```

**JSON 메타데이터 구조** (ProductMetadata)
```
metadata_json (30-50KB)
├── descriptions (다국어 설명: ko, en, zh, ja)
├── detailedDescription (상세 설명)
├── sizeOptions[] (사이즈 옵션 배열)
│   ├── size, measurements, fitType, available
├── colorOptions[] (색상 옵션 배열)
│   ├── colorName, colorCode, hexCode, imageUrls[], premium
├── reviews[] (리뷰 배열, 10-50개)
│   ├── reviewId, rating, title, content
│   ├── author (작성자 정보)
│   ├── verifiedPurchase, helpfulCount
│   ├── images[], purchasedSize, purchasedColor
├── styleRecommendations[] (스타일링 추천)
│   ├── style, occasion, season[]
│   ├── matchingItems[] (코디 아이템)
├── materialInfo (소재 정보)
│   ├── composition (면 80%, 폴리 20% 등)
│   ├── origin, certifications[], features[]
├── careInstructions (세탁 방법, 다국어)
├── shippingPolicy (배송 정책)
│   ├── domesticShipping, internationalShipping
│   ├── freeShippingThreshold, estimatedDays
├── returnPolicy (반품 정책)
├── brandInfo (브랜드 정보)
├── categoryTags[], seasonTags[]
├── images[] (5-10개)
│   ├── imageId, url, type, order, width, height
├── relatedProducts[] (연관 상품 ID)
└── inventory{} (재고 정보 맵)
    └── "SIZE-COLOR" -> InventoryDetail
```

### 테스트 데이터

- **데이터 개수**: 100개
- **JSON 크기**: 평균 30-50KB (상품당)
- **총 데이터 크기**: 약 3-5MB
- **초기화**: 애플리케이션 시작 시 데이터가 없으면 자동 생성
- **재실행 시**: 기존 데이터 유지 (중복 생성 방지)

### API 엔드포인트

#### Product API

- **단일 상품 조회**: `GET /api/products/{id}`
  - JSON 역직렬화 수행
  - 전체 메타데이터 반환

- **상품 목록 조회**: `GET /api/products?page=0&size=10`
  - 페이징 지원 (기본 10개)
  - 각 상품마다 JSON 역직렬화 수행
  - 요약 정보 반환 (이미지, 평균 평점 등)

- **상품 검색**: `GET /api/products/search?name=베이직&page=0&size=10`
  - 이름으로 검색
  - 페이징 지원

#### Actuator

- **Health Check**: `GET /actuator/health`
- **전체 메트릭**: `GET /actuator/metrics`
- **역직렬화 성능**: `GET /actuator/metrics/product.deserialization.time`
- **API 응답 시간**: `GET /actuator/metrics/api.product.get.by.id`

### 성능 메트릭

Micrometer를 사용한 커스텀 메트릭 수집:

1. **product.deserialization.time**
   - JSON → Kotlin 객체 변환 시간 측정
   - 단위: 나노초
   - Percentile: p50, p95, p99

2. **api.product.get.by.id**
   - 단일 상품 조회 API 응답 시간
   - DB 조회 + 역직렬화 포함

3. **api.product.get.all**
   - 목록 조회 API 응답 시간
   - 여러 상품의 역직렬화 성능 측정

4. **api.product.search**
   - 검색 API 응답 시간

### 성능 측정 예시

```bash
# 단일 상품 조회
curl http://localhost:8080/api/products/1

# 10개 상품 조회 (역직렬화 10회)
curl http://localhost:8080/api/products?size=10

# 역직렬화 성능 메트릭 확인
curl http://localhost:8080/actuator/metrics/product.deserialization.time

# 결과 예시:
{
  "name": "product.deserialization.time",
  "measurements": [
    { "statistic": "COUNT", "value": 100 },
    { "statistic": "TOTAL_TIME", "value": 0.524 },
    { "statistic": "MAX", "value": 0.012 }
  ],
  "availableTags": []
}
```

### 로그 분석

역직렬화 시간이 10ms를 초과하면 경고 로그 출력:

```
WARN: Slow JSON deserialization detected: 15ms for 45623 bytes
```

## 보안 참고사항

- **절대 커밋하지 말 것**:
  - `ssm-tunnel-config.sh` (실제 EC2/RDS 정보)
  - `application-local.yml` (로컬 비밀번호)
  - AWS 자격증명 파일
  - `.pem`, `.key` 파일

- `.gitignore`에 이미 포함되어 있으므로 실수로 커밋되지 않습니다

## 트러블슈팅

### ParameterStore 연결 실패

```
Could not resolve placeholder 'my-first-database-host'
```

**해결방법**:
1. AWS CLI 자격증명 확인: `aws configure list`
2. Parameter Store에 파라미터 등록 확인
3. IAM 권한 확인 (ssm:GetParameter)

### RDS 연결 타임아웃

```
Communications link failure
```

**해결방법**:
1. SSM 포트 포워딩이 실행 중인지 확인
2. RDS 보안 그룹에 EC2 보안 그룹 허용 확인
3. EC2 인스턴스가 RDS와 같은 VPC에 있는지 확인

### Session Manager 연결 실패

**해결방법**:
1. Session Manager 플러그인 설치 확인
2. EC2 인스턴스에 `AmazonSSMManagedInstanceCore` 역할 확인
3. EC2 인스턴스가 실행 중인지 확인

## 라이선스

MIT License