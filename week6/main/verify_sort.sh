#!/bin/bash
# Verify that partition files are sorted correctly

OUTPUT_DIR="${1:-output}"

echo "=== Partition Sort Verification ==="
echo "Checking directory: $OUTPUT_DIR"
echo

# Check if output directory exists
if [ ! -d "$OUTPUT_DIR" ]; then
    echo "❌ Error: Directory $OUTPUT_DIR does not exist"
    exit 1
fi

# Get all partition files sorted by number
PARTITIONS=$(ls "$OUTPUT_DIR"/partition.* 2>/dev/null | sort -t. -k2 -n)

if [ -z "$PARTITIONS" ]; then
    echo "❌ No partition files found in $OUTPUT_DIR"
    exit 1
fi

echo "Found partition files:"
echo "$PARTITIONS" | nl
echo

# Count partitions
TOTAL=$(echo "$PARTITIONS" | wc -l)
echo "Total partitions: $TOTAL"
echo

# Check if valsort is available
if command -v valsort &> /dev/null; then
    echo "Using valsort for verification..."
    valsort $PARTITIONS
    RESULT=$?

    if [ $RESULT -eq 0 ]; then
        echo "✅ All partitions are correctly sorted!"
    else
        echo "❌ Partitions are NOT correctly sorted"
    fi
    exit $RESULT
else
    echo "⚠️  valsort not found, performing basic checks..."
    echo

    # Basic checks without valsort
    ALL_OK=true

    # Check file sizes
    echo "=== File Sizes ==="
    for f in $PARTITIONS; do
        SIZE=$(stat -f%z "$f" 2>/dev/null || stat -c%s "$f" 2>/dev/null)
        printf "%-30s %10d bytes\n" "$f" "$SIZE"

        if [ "$SIZE" -eq 0 ]; then
            echo "  ❌ Empty file!"
            ALL_OK=false
        fi
    done
    echo

    # Check record count (assuming 100-byte records)
    echo "=== Record Counts ==="
    for f in $PARTITIONS; do
        SIZE=$(stat -f%z "$f" 2>/dev/null || stat -c%s "$f" 2>/dev/null)
        RECORDS=$((SIZE / 100))
        printf "%-30s %10d records\n" "$f" "$RECORDS"
    done
    echo

    if [ "$ALL_OK" = true ]; then
        echo "✅ Basic checks passed (all files non-empty)"
        echo "⚠️  Note: Full sort verification requires valsort"
    else
        echo "❌ Basic checks failed"
        exit 1
    fi
fi
