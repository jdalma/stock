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

## API 엔드포인트

### Actuator

- Health Check: `GET /actuator/health`
- Metrics: `GET /actuator/metrics`
- Info: `GET /actuator/info`

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