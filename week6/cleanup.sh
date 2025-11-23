#!/bin/bash
# Cleanup script - Kill all distsort processes

echo "=== Cleaning up distsort processes ==="

# 1. Kill all Java processes running distsort
echo "Killing Java distsort processes..."
pkill -f "distsort.master.Master" 2>/dev/null && echo "  ✓ Master processes killed"
pkill -f "distsort.worker.Worker" 2>/dev/null && echo "  ✓ Worker processes killed"

# 2. Kill any remaining Java processes with distsort.jar
pkill -f "distsort.jar" 2>/dev/null && echo "  ✓ JAR processes killed"

# 3. Kill any sbt processes
pkill -f "sbt.*distsort" 2>/dev/null && echo "  ✓ SBT processes killed"

# 4. Show remaining distsort processes (if any)
echo ""
echo "=== Checking for remaining processes ==="
REMAINING=$(ps aux | grep -E "distsort|master|worker" | grep -v grep | grep -v cleanup.sh)
if [ -z "$REMAINING" ]; then
    echo "  ✓ All processes cleaned up"
else
    echo "  ⚠ Some processes still running:"
    echo "$REMAINING"
    echo ""
    echo "Force kill? (y/n)"
    read -r answer
    if [ "$answer" = "y" ]; then
        ps aux | grep -E "distsort|master|worker" | grep -v grep | awk '{print $2}' | xargs kill -9 2>/dev/null
        echo "  ✓ Force killed remaining processes"
    fi
fi

# 5. Clean up temporary files
echo ""
echo "=== Cleaning temporary files ==="
rm -rf /tmp/distsort/* 2>/dev/null && echo "  ✓ Cleared /tmp/distsort/"
rm -rf /tmp/master*.log /tmp/worker*.log /tmp/test*.log /tmp/tw*.log /tmp/ww*.log 2>/dev/null && echo "  ✓ Cleared log files"

echo ""
echo "=== Cleanup complete ==="
