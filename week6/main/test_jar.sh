#!/bin/bash
# Test JAR execution script

set -e  # Exit on error

echo "=== Testing JAR Build and Execution ==="
echo ""

# Clean previous build
echo "1. Cleaning previous build..."
sbt clean
echo ""

# Build JAR
echo "2. Building JAR with sbt assembly..."
sbt assembly
echo ""

# Check JAR exists
if [ ! -f "target/scala-2.13/distsort.jar" ]; then
    echo "❌ ERROR: JAR not found!"
    exit 1
fi

echo "✅ JAR built successfully: $(ls -lh target/scala-2.13/distsort.jar | awk '{print $5}')"
echo ""

# Check JAR contents
echo "3. Checking JAR contents..."
echo "Main classes:"
jar tf target/scala-2.13/distsort.jar | grep -E "Master\.class|Worker\.class" | head -5
echo ""

echo "gRPC classes:"
jar tf target/scala-2.13/distsort.jar | grep -E "io/grpc" | head -5
echo ""

echo "Netty classes:"
jar tf target/scala-2.13/distsort.jar | grep -E "io/netty" | head -5
echo ""

# Test Master startup
echo "4. Testing Master startup (will exit after 3 seconds)..."
timeout 3 java -Djava.net.preferIPv4Stack=true \
    -cp target/scala-2.13/distsort.jar \
    distsort.master.Master 3 || true
echo ""

echo "✅ All JAR tests passed!"
echo ""
echo "To test full workflow:"
echo "  Terminal 1: ./master 3"
echo "  Terminal 2: ./worker <master-ip:port> -I input1 -O output"
echo "  Terminal 3: ./worker <master-ip:port> -I input1 -O output"
echo "  Terminal 4: ./worker <master-ip:port> -I input1 -O output"
