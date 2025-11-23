# Test Input Directory

이 디렉토리는 분산 정렬 시스템 테스트를 위한 입력 데이터를 포함합니다.

## 사용 가능한 테스트 데이터

### 1. 소규모 테스트 (이 디렉토리)
```
test_input/
├── README.md          # 이 파일
└── sample.bin         # 샘플 테스트 데이터 (100KB, 1000 레코드)
```

- **파일**: `sample.bin`
- **크기**: 100KB
- **레코드 수**: 1,000개
- **용도**: 빠른 기능 테스트, 디버깅

### 2. 대규모 테스트 (input1-4 디렉토리)
```
main/
├── input1/data.bin    # 62MB (650,000 레코드)
├── input2/data.bin    # 62MB (650,000 레코드)
├── input3/data.bin    # 62MB (650,000 레코드)
└── input4/data.bin    # 62MB (650,000 레코드)
```

- **총 크기**: 248MB
- **총 레코드**: 2,600,000개
- **용도**: 실제 성능 테스트, 대용량 데이터 검증

## 공통 레코드 형식

- **레코드 크기**: 100-byte
- **구조**: 10-byte key + 90-byte value
- **생성 방법**: `/dev/urandom`으로 생성된 랜덤 데이터

## 사용 방법

### 소규모 빠른 테스트 (100KB)
```bash
# Master 실행
./master 3

# Worker 실행 (각 터미널에서)
./worker <master-ip:port> -I test_input -O test_output
./worker <master-ip:port> -I test_input -O test_output
./worker <master-ip:port> -I test_input -O test_output
```

### 대규모 성능 테스트 (248MB)
```bash
# Master 실행
./master 3

# Worker 실행 - 각각 다른 입력 디렉토리 사용
./worker <master-ip:port> -I input1 -O output
./worker <master-ip:port> -I input2 -O output
./worker <master-ip:port> -I input3 -O output

# 또는 모든 Worker가 같은 입력 사용
./worker <master-ip:port> -I input1 -O output
./worker <master-ip:port> -I input1 -O output
./worker <master-ip:port> -I input1 -O output
```

**실제 클러스터 테스트에서 사용된 방법:**
- 입력: `input1/` (62MB, 650k 레코드)
- Worker 3개가 모두 같은 입력 읽기
- 출력: 9개 파티션 (총 63MB, 630k 레코드)
- 처리 시간: ~10초

### 더 큰 테스트 데이터 생성

```bash
# 62MB 테스트 데이터 (650,000 레코드)
dd if=/dev/urandom of=test_input/large.bin bs=100 count=650000

# 여러 입력 파일로 테스트
dd if=/dev/urandom of=test_input/data1.bin bs=100 count=100000
dd if=/dev/urandom of=test_input/data2.bin bs=100 count=100000
dd if=/dev/urandom of=test_input/data3.bin bs=100 count=100000
```

## 출력 검증

테스트 완료 후 `test_output/` 디렉토리에 파티션 파일들이 생성됩니다:
```bash
# 생성된 파일 확인
ls -lh test_output/

# 정렬 검증 (선택사항)
./verify_sort.sh test_output
```
