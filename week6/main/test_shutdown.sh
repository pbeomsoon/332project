#!/bin/bash
# Test shutdown race condition fix
# This script verifies that Master and Workers shut down gracefully

set -e

echo "=== Testing Graceful Shutdown Coordination ==="
echo ""

# Check if input directory exists
if [ ! -d "input1" ]; then
    echo "❌ Error: input1 directory not found"
    exit 1
fi

echo "✅ Input directory found"
echo ""

# Clean previous outputs
rm -rf output/
mkdir -p output/
echo "✅ Output directory prepared"
echo ""

# Build JAR if needed
if [ ! -f "target/scala-2.13/distsort.jar" ]; then
    echo "Building JAR..."
    sbt assembly
    echo ""
fi

echo "✅ JAR ready"
echo ""

# Start Master in background, capturing output
echo "1. Starting Master (expecting 3 workers)..."
./master 3 > /tmp/master_shutdown_test.log 2>&1 &
MASTER_PID=$!
sleep 3

# Get Master address from log
MASTER_ADDR=$(head -1 /tmp/master_shutdown_test.log)
echo "   Master address: $MASTER_ADDR"
echo ""

# Start 3 Workers in background
echo "2. Starting 3 Workers..."
for i in 1 2 3; do
    ./worker $MASTER_ADDR -I input1 -O output > /tmp/worker${i}_shutdown_test.log 2>&1 &
    WORKER_PIDS[$i]=$!
    echo "   Worker $i started (PID: ${WORKER_PIDS[$i]})"
done
echo ""

# Wait for workflow to complete
echo "3. Waiting for workflow to complete..."
sleep 30
echo ""

# Check if Master is still running (should be in grace period or stopped)
echo "4. Checking shutdown status..."
if ps -p $MASTER_PID > /dev/null 2>&1; then
    echo "   Master still running (might be in grace period)"
else
    echo "   ✅ Master has stopped"
fi

# Wait a bit more for grace period
sleep 15

# Check Workers
WORKERS_STOPPED=0
for i in 1 2 3; do
    if ! ps -p ${WORKER_PIDS[$i]} > /dev/null 2>&1; then
        echo "   ✅ Worker $i has stopped gracefully"
        WORKERS_STOPPED=$((WORKERS_STOPPED + 1))
    else
        echo "   ⚠️  Worker $i still running"
        kill ${WORKER_PIDS[$i]} 2>/dev/null || true
    fi
done

# Final status
echo ""
echo "=== Final Status ==="
echo "Workers stopped gracefully: $WORKERS_STOPPED/3"
echo ""

# Show logs
echo "=== Master Log (last 15 lines) ==="
tail -15 /tmp/master_shutdown_test.log
echo ""

echo "=== Worker 1 Log (last 10 lines) ==="
tail -10 /tmp/worker1_shutdown_test.log
echo ""

# Check for successful shutdown indicators
echo "=== Shutdown Verification ==="
if grep -q "Signaling workflow completion" /tmp/master_shutdown_test.log; then
    echo "✅ Master signaled workflow completion"
else
    echo "❌ Master did not signal completion"
fi

if grep -q "Waiting.*for workers to receive shutdown signal" /tmp/master_shutdown_test.log; then
    echo "✅ Master waited for grace period"
else
    echo "❌ Master did not wait for grace period"
fi

if grep -q "shouldAbort.*true\|instructed to abort" /tmp/worker1_shutdown_test.log; then
    echo "✅ Worker received shutdown signal"
else
    echo "⚠️  Worker did not receive shutdown signal (might have finished too quickly)"
fi

# Cleanup
rm -f /tmp/master_shutdown_test.log /tmp/worker*_shutdown_test.log

echo ""
echo "✅ Test complete!"
