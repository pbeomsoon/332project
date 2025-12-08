# Distributed Sorting System

POSTECH CSED332 - Software Design Methods Project

**Team Silver**: 권동연, 박범순, 임지훈

---

## 접속 방법

### Master 서버 접속
```bash
ssh -p 7777 silver@141.223.16.227
```

### Master에서 Worker 서버 접속
```bash
ssh silver@2.2.2.101
ssh silver@2.2.2.102
# ... (각 Worker IP로 접속)
```

---

## 빌드

Master 서버에서 실행:
```bash
cd ~/main && sbt clean assembly
```

---

## Worker에 실행파일 전송

Master 서버에서 실행:
```bash
for i in $(seq 101 120); do
  scp ~/main/target/scala-2.13/distsort.jar silver@2.2.2.$i:~/
done
```

---

## 실행 방법

**각 Master/Worker는 별도의 터미널에서 실행합니다!**

### 1. Master 실행 (Terminal 1 - Master 서버)
```bash
cd ~/main 
./master <worker 수>
```

예시:
```bash
./master 3
```

실행 후 출력되는 **Master 주소 (IP:PORT)** 를 확인하세요!

### 2. Worker 실행 (Terminal 2, 3, 4... - 각 Worker 서버)

각 Worker 서버에 SSH 접속 후 실행합니다.

**주의: Master 주소(PORT)는 실행할 때마다 변경됩니다!**

```bash
java -cp ~/distsort.jar distsort.worker.Worker <MASTER_IP:PORT> -I <input> -O <output>
```

예시:
```bash
# Small dataset
java -cp ~/distsort.jar distsort.worker.Worker 2.2.2.254:45277 -I /dataset/small -O /home/silver/output/small

# Big dataset
java -cp ~/distsort.jar distsort.worker.Worker 2.2.2.254:35581 -I /dataset/big -O /home/silver/output/big

# Large dataset
java -cp ~/distsort.jar distsort.worker.Worker 2.2.2.254:39894 -I /dataset/large -O /home/silver/output/large
```

---

## Fault Tolerance 데모

**데모 방법:**

1. 정상적으로 Master와 Worker들을 실행
2. **Sort 단계 중에** Worker 하나를 `Ctrl+C`로 종료 (Sort 중 종료 권장!)
3. 같은 서버에서 **동일한 명령어로 Worker 재실행**
4. Master가 실패를 감지하고 복구 Worker를 인식
5. 기다리시면 자동으로 복구되어 작업 완료!

---

## 결과 검증

각 Worker의 output 경로에서 실행:
```bash
cd /home/silver/output/small  # 또는 big, large

ls partition.* | sort -t. -k2 -n | xargs cat > /tmp/all_output
valsort /tmp/all_output
```

성공 시 출력:
```
Records: XXXXXX: OK
```

---

## 요약

| 단계 | 명령어 |
|------|--------|
| 빌드 | `cd ~/main && sbt clean assembly` |
| 배포 | `for i in $(seq 101 120); do scp ~/main/target/scala-2.13/distsort.jar silver@2.2.2.$i:~/; done` |
| Master | `./master <n>` |
| Worker | `java -cp ~/distsort.jar distsort.worker.Worker <IP:PORT> -I <input> -O <output>` |
| 검증 | `ls partition.* \| sort -t. -k2 -n \| xargs cat > /tmp/all_output && valsort /tmp/all_output` |
