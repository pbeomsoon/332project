# Test Output Directory

이 디렉토리는 분산 정렬 시스템의 출력 파일이 저장됩니다.

## 출력 형식

Worker들이 정렬 작업을 완료하면 다음과 같은 파티션 파일들이 생성됩니다:

```
test_output/
├── README.md          # 이 파일
├── partition.0        # 파티션 0 (가장 작은 키 범위)
├── partition.1        # 파티션 1
├── partition.2        # 파티션 2
├── ...
└── partition.N        # 파티션 N (가장 큰 키 범위)
```

## 파티션 수

파티션 수는 Worker 수와 설정에 따라 결정됩니다:
- **Strategy A (1:1)**: N workers → N partitions
- **Strategy B (N:M)**: N workers → M partitions (기본: N × 3)

예시:
- 3 Workers → 9 partitions (partition.0 ~ partition.8)
- 2 Workers → 6 partitions (partition.0 ~ partition.5)

## 검증

### 파일 확인
```bash
# 생성된 파티션 확인
ls -lh test_output/

# 총 크기 확인
du -sh test_output/
```

### 정렬 검증
```bash
# verify_sort.sh 스크립트 사용
./verify_sort.sh test_output

# 수동 검증 (첫 번째 파티션)
od -An -tx1 -N 100 test_output/partition.0
```

## 정리

테스트 후 출력 파일 정리:
```bash
# 모든 파티션 삭제
rm -f test_output/partition.*

# 전체 디렉토리 정리
rm -rf test_output/*
mkdir -p test_output  # 빈 디렉토리 재생성
```
