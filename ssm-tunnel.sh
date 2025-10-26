#!/bin/bash

# SSM Session Manager를 통한 RDS 포트 포워딩 스크립트
# 사용법: ./ssm-tunnel.sh

set -e

# 설정 파일 확인
CONFIG_FILE="./ssm-tunnel-config.sh"

if [ ! -f "$CONFIG_FILE" ]; then
    echo "❌ 설정 파일이 없습니다: $CONFIG_FILE"
    echo ""
    echo "다음 명령어로 설정 파일을 생성하세요:"
    echo "  cp ssm-tunnel-config.sh.example ssm-tunnel-config.sh"
    echo ""
    echo "그리고 ssm-tunnel-config.sh 파일을 열어 실제 값을 입력하세요:"
    echo "  - EC2_INSTANCE_ID: EC2 인스턴스 ID"
    echo "  - RDS_ENDPOINT: RDS 엔드포인트 주소"
    exit 1
fi

# 설정 파일 로드
source "$CONFIG_FILE"

# 필수 값 확인
if [ "$EC2_INSTANCE_ID" = "your-ec2-instance-id" ] || [ -z "$EC2_INSTANCE_ID" ]; then
    echo "❌ EC2_INSTANCE_ID를 설정해주세요 (ssm-tunnel-config.sh)"
    exit 1
fi

if [ "$RDS_ENDPOINT" = "your-rds-endpoint.ap-northeast-2.rds.amazonaws.com" ] || [ -z "$RDS_ENDPOINT" ]; then
    echo "❌ RDS_ENDPOINT를 설정해주세요 (ssm-tunnel-config.sh)"
    exit 1
fi

# AWS CLI 확인
if ! command -v aws &> /dev/null; then
    echo "❌ AWS CLI가 설치되어 있지 않습니다"
    echo "설치 방법: https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html"
    exit 1
fi

# Session Manager 플러그인 확인
if ! command -v session-manager-plugin &> /dev/null; then
    echo "❌ Session Manager 플러그인이 설치되어 있지 않습니다"
    echo ""
    echo "macOS 설치:"
    echo "  brew install --cask session-manager-plugin"
    echo ""
    echo "또는 수동 설치:"
    echo "  https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html"
    exit 1
fi

echo "🔧 SSM 포트 포워딩 시작..."
echo "  EC2 Instance: $EC2_INSTANCE_ID"
echo "  RDS Endpoint: $RDS_ENDPOINT"
echo "  Local Port: $LOCAL_PORT -> Remote Port: $RDS_PORT"
echo ""
echo "✅ 터널이 열리면 다른 터미널에서 애플리케이션을 실행하세요:"
echo "   ./gradlew bootRun"
echo ""
echo "🛑 종료하려면 Ctrl+C를 누르세요"
echo ""

# 포트 포워딩 시작
aws ssm start-session \
    --target "$EC2_INSTANCE_ID" \
    --document-name AWS-StartPortForwardingSessionToRemoteHost \
    --parameters "{\"host\":[\"$RDS_ENDPOINT\"],\"portNumber\":[\"$RDS_PORT\"],\"localPortNumber\":[\"$LOCAL_PORT\"]}"