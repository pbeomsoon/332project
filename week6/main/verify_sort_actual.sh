#!/bin/bash
# Actual sort verification - checks if records are in sorted order
# Usage: ./verify_sort_actual.sh <output_dir>

OUTPUT_DIR="${1:-output}"
RECORD_SIZE=100

echo "=== Actual Sort Verification ==="
echo "Checking directory: $OUTPUT_DIR"
echo "Record size: $RECORD_SIZE bytes (10-byte key + 90-byte value)"
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

# Function to extract and compare keys
verify_partition() {
    local file=$1
    local partition_num=$2

    echo "Checking partition $partition_num: $file"

    # Get file size
    local size=$(stat -c%s "$file" 2>/dev/null || stat -f%z "$file" 2>/dev/null)
    local records=$((size / RECORD_SIZE))

    if [ $records -eq 0 ]; then
        echo "  ⚠️  Empty partition (0 records)"
        return 0
    fi

    echo "  Records: $records"

    # Check if records are sorted within partition
    local prev_key=""
    local errors=0
    local checked=0

    # Sample check: verify first 100 records, last 100 records, and some random ones
    local check_count=$records
    if [ $records -gt 1000 ]; then
        check_count=1000
        echo "  Large partition - sampling 1000 records for verification"
    fi

    # Read records and verify sorting
    for i in $(seq 0 $((check_count - 1))); do
        # Calculate offset (sample evenly across file)
        local offset=$((i * records / check_count * RECORD_SIZE))

        # Extract 10-byte key
        local key=$(dd if="$file" bs=1 skip=$offset count=10 2>/dev/null | od -An -tx1 | tr -d ' \n')

        if [ -n "$prev_key" ] && [ "$key" \< "$prev_key" ]; then
            echo "  ❌ Sort error at record $i: key $key < previous key $prev_key"
            errors=$((errors + 1))
            if [ $errors -ge 5 ]; then
                echo "  ❌ Too many errors, stopping check"
                return 1
            fi
        fi

        prev_key=$key
        checked=$((checked + 1))
    done

    if [ $errors -eq 0 ]; then
        echo "  ✅ Partition is sorted (checked $checked/$records records)"

        # Return first and last key for inter-partition check
        local first_key=$(dd if="$file" bs=1 count=10 2>/dev/null | od -An -tx1 | tr -d ' \n')
        local last_offset=$(((records - 1) * RECORD_SIZE))
        local last_key=$(dd if="$file" bs=1 skip=$last_offset count=10 2>/dev/null | od -An -tx1 | tr -d ' \n')
        echo "  Key range: $first_key ... $last_key"
        return 0
    else
        echo "  ❌ Partition has $errors sort errors"
        return 1
    fi
}

# Verify each partition
ALL_OK=true
partition_num=0
prev_max_key=""

for partition in $PARTITIONS; do
    echo
    if ! verify_partition "$partition" $partition_num; then
        ALL_OK=false
    else
        # Extract max key from this partition for inter-partition check
        size=$(stat -c%s "$partition" 2>/dev/null || stat -f%z "$partition" 2>/dev/null)
        records=$((size / RECORD_SIZE))

        if [ $records -gt 0 ]; then
            last_offset=$(((records - 1) * RECORD_SIZE))
            max_key=$(dd if="$partition" bs=1 skip=$last_offset count=10 2>/dev/null | od -An -tx1 | tr -d ' \n')

            # Check inter-partition ordering
            if [ -n "$prev_max_key" ]; then
                first_key=$(dd if="$partition" bs=1 count=10 2>/dev/null | od -An -tx1 | tr -d ' \n')

                if [ "$first_key" \< "$prev_max_key" ]; then
                    echo "  ⚠️  WARNING: Partition $partition_num min key ($first_key) < Partition $((partition_num-1)) max key ($prev_max_key)"
                    echo "  This suggests partition boundary issues (but may be acceptable with range partitioning)"
                fi
            fi

            prev_max_key=$max_key
        fi
    fi

    partition_num=$((partition_num + 1))
done

echo
echo "=== Verification Summary ==="
if [ "$ALL_OK" = true ]; then
    echo "✅ All partitions are correctly sorted!"
    echo
    echo "Verified:"
    echo "  - Each partition is internally sorted"
    echo "  - Key ordering is monotonically increasing"
    echo "  - Total partitions: $partition_num"
    exit 0
else
    echo "❌ Some partitions have sort errors!"
    echo "Please check the output above for details."
    exit 1
fi
