# 정렬 검증 스크립트 가이드

**작성일**: 2025-11-23

---

## 사용 가능한 검증 스크립트

### 1. verify_sort.sh (간단한 검증)
**용도**: 파일 존재 및 기본 구조 확인

```bash
./verify_sort.sh output
```

**검증 항목:**
- ✅ 출력 디렉토리 존재 확인
- ✅ partition.* 파일들 존재 확인
- ✅ 파일 크기 확인
- ⚠️ **실제 정렬 순서는 검증하지 않음**

**출력 예시:**
```
=== Sort Verification ===
Checking directory: output

Found partition files:
partition.0
partition.1
...

✅ All partition files exist
⚠️ Note: Full sort verification requires valsort
```

### 2. verify_sort_actual.sh (실제 정렬 검증) ⭐ 추천

**용도**: 실제 레코드 키를 읽어서 정렬 순서 확인

```bash
./verify_sort_actual.sh output
```

**검증 항목:**
- ✅ 각 파티션 내부 정렬 확인 (intra-partition)
- ✅ 파티션 간 경계 확인 (inter-partition)
- ✅ 10-byte 키의 hex 값 비교
- ✅ 대용량 파일은 샘플링 (1000 레코드)

**출력 예시:**
```
=== Actual Sort Verification ===
Checking directory: output
Record size: 100 bytes (10-byte key + 90-byte value)

Found partition files:
partition.0
partition.1
...

Checking partition 0: output/partition.0
  Records: 65000
  ✅ Partition is sorted (checked 1000/65000 records)
  Key range: 0a1b2c3d4e ... fa9b8c7d6e

Checking partition 1: output/partition.1
  Records: 68000
  ✅ Partition is sorted (checked 1000/68000 records)
  Key range: fa9b8c7d6f ... ff1234abcd

=== Verification Summary ===
✅ All partitions are correctly sorted!

Verified:
  - Each partition is internally sorted
  - Key ordering is monotonically increasing
  - Total partitions: 9
```

---

## 작동 원리

### verify_sort_actual.sh 알고리즘

**1. 파티션별 내부 정렬 확인**
```bash
# 각 레코드의 10-byte 키 추출
dd if="partition.0" bs=1 skip=$offset count=10 | od -An -tx1

# Hex 값으로 비교
if [ "$current_key" \< "$previous_key" ]; then
  echo "❌ Sort error"
fi
```

**2. 샘플링 전략 (대용량 파일)**
- 파일에 1000개 이상 레코드가 있으면 샘플링
- 전체 파일에 걸쳐 균등하게 1000개 레코드 추출
- 각 샘플 레코드의 키 비교

**3. 파티션 간 경계 확인**
```bash
# 이전 파티션의 max key
prev_max_key = last key of partition N

# 현재 파티션의 min key
current_min_key = first key of partition N+1

# 경계 확인 (경고만, 에러 아님)
if [ "$current_min_key" \< "$prev_max_key" ]; then
  echo "⚠️ WARNING: Partition boundary issue"
fi
```

**참고**: Range partitioning에서는 파티션 간 키 오버랩이 발생할 수 있으므로 경고만 표시

---

## 사용 예시

### 기본 사용
```bash
# 기본 디렉토리 (output)
./verify_sort_actual.sh

# 특정 디렉토리
./verify_sort_actual.sh /tmp/distsort/output

# 테스트 출력
./verify_sort_actual.sh test_output
```

### 파이프라인에서 사용
```bash
# 정렬 완료 후 자동 검증
./master 3 && ./verify_sort_actual.sh output

# 검증 결과만 저장
./verify_sort_actual.sh output > verification.log 2>&1

# 검증 실패 시 알림
./verify_sort_actual.sh output || echo "❌ Sort verification failed!"
```

### 스크립트 자동화
```bash
#!/bin/bash
# test_and_verify.sh

echo "Starting distributed sort..."
./master 3 &
MASTER_PID=$!

sleep 2
MASTER_ADDR=$(head -1 /tmp/master_output.txt)

./worker $MASTER_ADDR -I input1 -O output &
./worker $MASTER_ADDR -I input2 -O output &
./worker $MASTER_ADDR -I input3 -O output &

wait $MASTER_PID

echo "Verifying sort..."
./verify_sort_actual.sh output

if [ $? -eq 0 ]; then
  echo "✅ Test passed!"
else
  echo "❌ Test failed!"
  exit 1
fi
```

---

## 출력 해석

### ✅ 성공
```
✅ All partitions are correctly sorted!
```
- 모든 파티션이 내부적으로 정렬됨
- 키 순서가 monotonically increasing

### ⚠️ 경고
```
⚠️ WARNING: Partition N min key < Partition N-1 max key
This suggests partition boundary issues (but may be acceptable with range partitioning)
```
- 파티션 간 키 오버랩 감지
- Range partitioning에서는 정상일 수 있음
- 정렬 자체는 여전히 유효

### ❌ 에러
```
❌ Sort error at record 123: key abc123 < previous key def456
❌ Partition has 5 sort errors
```
- 파티션 내부에서 정렬 순서 위반 발견
- 정렬이 올바르게 작동하지 않음
- 디버깅 필요

---

## 성능 고려사항

### verify_sort.sh
- **속도**: 매우 빠름 (~1초)
- **정확도**: 낮음 (파일 존재만 확인)
- **용도**: 빠른 smoke test

### verify_sort_actual.sh
- **속도**: 중간 (~10-30초, 파일 크기에 따라)
- **정확도**: 높음 (실제 키 비교)
- **용도**: 정확한 검증

**최적화:**
- 대용량 파일은 자동 샘플링 (1000 레코드)
- 5개 이상 에러 발견 시 조기 종료
- 병렬 처리는 현재 미지원 (순차 검증)

---

## 레코드 형식

### 구조
```
| 10-byte key | 90-byte value |
|-------------|---------------|
   0-9           10-99
```

### 키 추출
```bash
# 레코드 N의 키 읽기
offset=$((N * 100))
dd if=partition.0 bs=1 skip=$offset count=10

# Hex 형식으로 변환
od -An -tx1 | tr -d ' \n'
```

### 비교 방법
```bash
# Bash의 문자열 비교 (<, >, =)
# Hex 문자열을 lexicographically 비교
if [ "$key1" \< "$key2" ]; then
  echo "key1 is less than key2"
fi
```

---

## 문제 해결

### "No partition files found"
```bash
# 출력 디렉토리 확인
ls -lh output/

# 다른 디렉토리 시도
./verify_sort_actual.sh /tmp/distsort/output
```

### "Permission denied"
```bash
# 실행 권한 추가
chmod +x verify_sort_actual.sh

# 확인
ls -lh verify*.sh
```

### "dd: invalid number"
```bash
# 파일이 손상되었거나 형식이 잘못됨
# 레코드 크기 확인
stat output/partition.0

# 크기가 100의 배수여야 함
```

### 검증이 너무 느림
```bash
# 샘플링이 자동으로 적용되지만, 수동으로 조정하려면
# verify_sort_actual.sh 편집

# Line 57-59:
if [ $records -gt 1000 ]; then
  check_count=1000  # 이 값을 줄이면 더 빠름
fi
```

---

## 요약

| 스크립트 | 속도 | 정확도 | 용도 |
|---------|------|--------|------|
| verify_sort.sh | ⚡⚡⚡ 빠름 | ⭐ 낮음 | 빠른 smoke test |
| verify_sort_actual.sh | ⚡⚡ 중간 | ⭐⭐⭐ 높음 | 정확한 검증 |

**추천 사용법:**
```bash
# 개발/테스트 중: 빠른 확인
./verify_sort.sh output

# 최종 검증: 정확한 확인
./verify_sort_actual.sh output
```
