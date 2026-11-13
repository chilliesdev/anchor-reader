#!/usr/bin/env bash
set -euo pipefail

# Supertonic 3 HD TTS Speech Latency Benchmark Runner
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

if [[ -z "${JAVA_HOME:-}" && -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]]; then
  export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
fi

adb_bin="$ANDROID_HOME/platform-tools/adb"
DEVICE_ID="${ANDROID_SERIAL:-emulator-5554}"
export ANDROID_SERIAL="$DEVICE_ID"

evidence_dir="${ANCHOR_QA_OUTPUT:-$project_dir/.tmp/qa}"
mkdir -p "$evidence_dir"
raw_log="$evidence_dir/supertonic_benchmark_raw.log"
benchmark_log="$evidence_dir/supertonic_benchmark.log"
summary_file="$evidence_dir/supertonic_benchmark_summary.txt"

echo "Checking target device: $DEVICE_ID..."
"$adb_bin" -s "$DEVICE_ID" get-state >/dev/null

# 1. Prepare Voice Pack Models on Target Device
HOST_FIXTURE_PATH="${SUPERTONIC_FIXTURE_PATH:-/Users/apple/.gemini/antigravity/brain/2d507472-3074-45c5-a678-18bae3fd220d/scratch/real_tts/extracted/sherpa-onnx-supertonic-3-tts-int8-2026-05-11}"
DEVICE_TMP_DIR="/data/local/tmp/supertonic_voice"

models_ready=true
for req_file in duration_predictor.int8.onnx text_encoder.int8.onnx vector_estimator.int8.onnx vocoder.int8.onnx tts.json unicode_indexer.bin voice.bin; do
  if ! "$adb_bin" -s "$DEVICE_ID" shell "[ -f $DEVICE_TMP_DIR/$req_file ]"; then
    models_ready=false
    break
  fi
done

if [[ "$models_ready" != "true" ]]; then
  if [[ -d "$HOST_FIXTURE_PATH" ]]; then
    echo "Staging Supertonic voice models from host fixture to $DEVICE_TMP_DIR..."
    "$adb_bin" -s "$DEVICE_ID" shell "mkdir -p $DEVICE_TMP_DIR"
    "$adb_bin" -s "$DEVICE_ID" push "$HOST_FIXTURE_PATH/." "$DEVICE_TMP_DIR/"
  else
    echo "Warning: Host fixture path not found: $HOST_FIXTURE_PATH"
  fi
else
  echo "Supertonic voice models already present on $DEVICE_ID at $DEVICE_TMP_DIR."
fi

# 2. Clear logcat buffer before benchmark
"$adb_bin" -s "$DEVICE_ID" logcat -c

# 3. Run instrumented benchmark test via Gradle
echo "Executing SupertonicBenchmarkDeviceTest on $DEVICE_ID..."
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.anchorreader.supertonic.SupertonicBenchmarkDeviceTest \
  | tee "$raw_log"

# 4. Extract structured benchmark metrics from logcat
"$adb_bin" -s "$DEVICE_ID" logcat -d -s SupertonicBenchmark:I > "$benchmark_log"

# 5. Parse and generate summary report
python3 - << 'EOF' > "$summary_file"
import re
import sys
import os

benchmark_log_path = os.environ.get("benchmark_log", ".tmp/qa/supertonic_benchmark.log")
raw_log_path = os.environ.get("raw_log", ".tmp/qa/supertonic_benchmark_raw.log")
device_id = os.environ.get("DEVICE_ID", "emulator-5554")

log_content = ""
if os.path.exists(benchmark_log_path):
    with open(benchmark_log_path, "r", encoding="utf-8", errors="ignore") as f:
        log_content += f.read()
if os.path.exists(raw_log_path):
    with open(raw_log_path, "r", encoding="utf-8", errors="ignore") as f:
        log_content += "\n" + f.read()

# Parsers
session_match = re.search(r"SESSION_INIT\s+cold_ms=([\d\.]+)\s+warm_ms=([\d\.]+)\s+provider=(\w+)", log_content)
cb_fast = re.search(r"COMPONENT_BREAKDOWN\s+mode=FAST_3_STEP\s+dp_ms=([\d\.]+)\s+te_ms=([\d\.]+)\s+ve_ms=([\d\.]+)\s+vocoder_ms=([\d\.]+)\s+total_ms=([\d\.]+)\s+audio_sec=([\d\.]+)\s+rtf=([\d\.]+)", log_content)
cb_studio = re.search(r"COMPONENT_BREAKDOWN\s+mode=STUDIO_5_STEP\s+dp_ms=([\d\.]+)\s+te_ms=([\d\.]+)\s+ve_ms=([\d\.]+)\s+vocoder_ms=([\d\.]+)\s+total_ms=([\d\.]+)\s+audio_sec=([\d\.]+)\s+rtf=([\d\.]+)", log_content)
ttfa_fast = re.search(r"STREAMING_TTFA\s+mode=FAST_3_STEP\s+clause1_ttfa_ms=([\d\.]+)\s+total_stream_ms=([\d\.]+)", log_content)
ttfa_studio = re.search(r"STREAMING_TTFA\s+mode=STUDIO_5_STEP\s+clause1_ttfa_ms=([\d\.]+)\s+total_stream_ms=([\d\.]+)", log_content)
lookahead_match = re.search(r"LOOKAHEAD_CACHE\s+hit_ms=([\d\.]+)\s+miss_ms=([\d\.]+)\s+speedup_ratio=([\d\.]+x?)", log_content)
initial_chunk_match = re.search(r"INITIAL_STREAMING_CHUNK\s+chunk='([^']+)'\s+words=(\d+)\s+chars=(\d+)", log_content)
transition_match = re.search(r"MULTI_SENTENCE_TRANSITION\s+s0_to_s1_ms=([\d\.]+)\s+s1_to_s2_ms=([\d\.]+)", log_content)

print("=" * 80)
print("             SUPERTONIC 3 HD TTS ON-DEVICE LATENCY BENCHMARK REPORT")
print("=" * 80)
print(f"Target Device:       {device_id}")

if session_match:
    cold_ms, warm_ms, provider = session_match.groups()
    speedup = float(cold_ms) / max(0.001, float(warm_ms))
    print("\n1. SESSION INITIALIZATION LATENCY")
    print("-" * 80)
    print(f"  Active Inference Provider: {provider}")
    print(f"  Cold Initialization:       {float(cold_ms):>8.2f} ms")
    print(f"  Warm Initialization:       {float(warm_ms):>8.2f} ms")
    print(f"  Warm Init Speedup:         {speedup:>8.1f}x")
else:
    print("\n1. SESSION INITIALIZATION LATENCY: Data missing")

print("\n2. COMPONENT BREAKDOWN LATENCY (Utterance: 'Anchor Reader provides private offline document reading.')")
print("-" * 80)
print(f"  {'Quality Mode':<14} | {'DP (ms)':<9} | {'TE (ms)':<9} | {'VE (ms)':<9} | {'Vocoder (ms)':<12} | {'Total (ms)':<10} | {'Audio (s)':<9} | {'RTF':<6}")
print("  " + "-" * 88)

rtf_fast_val = None
if cb_fast:
    dp, te, ve, voc, tot, aud, rtf = cb_fast.groups()
    rtf_fast_val = float(rtf)
    print(f"  {'FAST_3_STEP':<14} | {float(dp):>9.2f} | {float(te):>9.2f} | {float(ve):>9.2f} | {float(voc):>12.2f} | {float(tot):>10.2f} | {float(aud):>9.2f} | {rtf_fast_val:>6.2f}")

rtf_studio_val = None
if cb_studio:
    dp, te, ve, voc, tot, aud, rtf = cb_studio.groups()
    rtf_studio_val = float(rtf)
    print(f"  {'STUDIO_5_STEP':<14} | {float(dp):>9.2f} | {float(te):>9.2f} | {float(ve):>9.2f} | {float(voc):>12.2f} | {float(tot):>10.2f} | {float(aud):>9.2f} | {rtf_studio_val:>6.2f}")

print("\n3. CLAUSE 1 STREAMING TIME-TO-FIRST-AUDIO (TTFA) (Paragraph: 2 clauses)")
print("-" * 80)
print(f"  {'Quality Mode':<14} | {'Clause 1 TTFA (ms)':<20} | {'Total Stream Duration (ms)':<28}")
print("  " + "-" * 68)

ttfa_fast_val = None
if ttfa_fast:
    ttfa, tot = ttfa_fast.groups()
    ttfa_fast_val = float(ttfa)
    print(f"  {'FAST_3_STEP':<14} | {ttfa_fast_val:>20.2f} | {float(tot):>28.2f}")

ttfa_studio_val = None
if ttfa_studio:
    ttfa, tot = ttfa_studio.groups()
    ttfa_studio_val = float(ttfa)
    print(f"  {'STUDIO_5_STEP':<14} | {ttfa_studio_val:>20.2f} | {float(tot):>28.2f}")

print("\n4. LOOKAHEAD CACHE HIT VS MISS LATENCY")
print("-" * 80)
hit_ms_val = None
if lookahead_match:
    hit_ms, miss_ms, ratio = lookahead_match.groups()
    hit_ms_val = float(hit_ms)
    print(f"  Lookahead Cache Hit:       {hit_ms_val:>8.3f} ms")
    print(f"  Lookahead Cache Miss:      {float(miss_ms):>8.2f} ms")
    print(f"  Lookahead Hit Speedup:     {ratio:>8}")
else:
    print("  Data missing")

print("\n5. TARGET METRICS COMPLIANCE VERIFICATION")
print("-" * 80)
print(f"  {'Metric':<30} | {'Target Value':<18} | {'Measured Value':<20} | {'Status':<6}")
print("  " + "-" * 80)

all_passed = True

# Metric 1: Time-to-First-Audio (TTFA) < 200 ms
if ttfa_fast_val is not None:
    status_ttfa = "PASS" if ttfa_fast_val < 200.0 else "FAIL"
    if status_ttfa == "FAIL": all_passed = False
    print(f"  {'Time-to-First-Audio (TTFA)':<30} | {'< 200 ms':<18} | {f'{ttfa_fast_val:.2f} ms':<20} | {status_ttfa:<6}")
else:
    all_passed = False
    print(f"  {'Time-to-First-Audio (TTFA)':<30} | {'< 200 ms':<18} | {'MISSING':<20} | {'FAIL':<6}")

# Metric 2: Interactive Maximum TTFA < 300 ms
if ttfa_studio_val is not None:
    status_max_ttfa = "PASS" if ttfa_studio_val < 300.0 else "FAIL"
    if status_max_ttfa == "FAIL": all_passed = False
    print(f"  {'Interactive Maximum TTFA':<30} | {'< 300 ms':<18} | {f'{ttfa_studio_val:.2f} ms':<20} | {status_max_ttfa:<6}")
else:
    all_passed = False
    print(f"  {'Interactive Maximum TTFA':<30} | {'< 300 ms':<18} | {'MISSING':<20} | {'FAIL':<6}")

# Metric 3: Lookahead Cache Latency < 10 ms
if hit_ms_val is not None:
    status_cache = "PASS" if hit_ms_val < 10.0 else "FAIL"
    if status_cache == "FAIL": all_passed = False
    print(f"  {'Lookahead Cache Latency':<30} | {'< 10 ms':<18} | {f'{hit_ms_val:.3f} ms':<20} | {status_cache:<6}")
else:
    all_passed = False
    print(f"  {'Lookahead Cache Latency':<30} | {'< 10 ms':<18} | {'MISSING':<20} | {'FAIL':<6}")

# Metric 4: Real-Time Factor (RTF) < 0.50
if rtf_fast_val is not None and rtf_studio_val is not None:
    max_rtf = max(rtf_fast_val, rtf_studio_val)
    status_rtf = "PASS" if max_rtf < 0.50 else "FAIL"
    if status_rtf == "FAIL": all_passed = False
    print(f"  {'Real-Time Factor (RTF)':<30} | {'< 0.50':<18} | {f'{max_rtf:.2f} (FAST={rtf_fast_val:.2f})':<20} | {status_rtf:<6}")
else:
    all_passed = False
    print(f"  {'Real-Time Factor (RTF)':<30} | {'< 0.50':<18} | {'MISSING':<20} | {'FAIL':<6}")

# Metric 5: Initial Streaming Chunk 3-5 words (15-30 chars)
if initial_chunk_match:
    chunk_txt, w_count, c_count = initial_chunk_match.groups()
    w_int = int(w_count)
    c_int = int(c_count)
    status_chunk = "PASS" if (3 <= w_int <= 5 and 15 <= c_int <= 30) else "FAIL"
    if status_chunk == "FAIL": all_passed = False
    print(f"  {'Initial Streaming Chunk':<30} | {'3-5 w (15-30 c)':<18} | {f'{w_int} words ({c_int} chars)':<20} | {status_chunk:<6}")
else:
    all_passed = False
    print(f"  {'Initial Streaming Chunk':<30} | {'3-5 w (15-30 c)':<18} | {'MISSING':<20} | {'FAIL':<6}")

# Metric 6: Multi-Sentence Transition < 15 ms
if transition_match:
    t1_str, t2_str = transition_match.groups()
    t1_val = float(t1_str)
    t2_val = float(t2_str)
    max_t = max(t1_val, t2_val)
    status_trans = "PASS" if max_t < 15.0 else "FAIL"
    if status_trans == "FAIL": all_passed = False
    print(f"  {'Multi-Sentence Transition':<30} | {'< 15 ms':<18} | {f'{t1_val:.2f}/{t2_val:.2f} ms':<20} | {status_trans:<6}")
else:
    all_passed = False
    print(f"  {'Multi-Sentence Transition':<30} | {'< 15 ms':<18} | {'MISSING':<20} | {'FAIL':<6}")

print("=" * 80)
if not all_passed:
    print("RESULT: FAILED - One or more target metrics not met.")
    sys.exit(1)
else:
    print("RESULT: ALL TARGET METRICS ACHIEVED.")
EOF

cat "$summary_file"
echo ""
echo "Benchmark artifacts saved:"
echo "- Summary: $summary_file"
echo "- Raw Log: $raw_log"
echo "- Logcat:  $benchmark_log"
